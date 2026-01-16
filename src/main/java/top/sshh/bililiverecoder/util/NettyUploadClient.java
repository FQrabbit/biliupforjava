package top.sshh.bililiverecoder.util;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;

import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class NettyUploadClient {
    // 共享的 EventLoopGroup，避免频繁创建线程
    private static final EventLoopGroup group = new NioEventLoopGroup(0, Executors.newCachedThreadPool());
    
    // 全局流量整形处理器 (GlobalTrafficShapingHandler)
    // checkInterval = 1000ms (默认), maxTime = 15000ms
    // 我们将其设为 checkInterval = 100ms 以获得更平滑的效果
    private static final GlobalTrafficShapingHandler trafficHandler = new GlobalTrafficShapingHandler(group, 0, 0, 100);

    /**
     * 获取全局写入吞吐量 (bytes/s)
     */
    public static long getGlobalWriteThroughput() {
        if (trafficHandler != null && trafficHandler.trafficCounter() != null) {
            return trafficHandler.trafficCounter().lastWriteThroughput();
        }
        return 0;
    }

    /**
     * 更新全局写入限速
     * @param writeLimit 写入限速 (bytes/s)，0 表示不限速
     */
    public static void updateWriteLimit(long writeLimit) {
        if (trafficHandler != null) {
            trafficHandler.setWriteLimit(writeLimit);
        }
    }

    /**
     * 使用 Netty 进行带限速的 PUT 上传
     * @param url 上传 URL
     * @param headers 请求头
     * @param params query 参数
     * @param file 文件句柄
     * @param start 文件起始偏移量
     * @param end 文件结束偏移量 (不包含)
     * @param timeoutMs 超时时间 (毫秒)
     * @param writeLimit 写入限速 (bytes/s)，0 表示不限速
     * @return 响应内容
     */
    public static String put(String url, Map<String, String> headers, Map<String, String> params, RandomAccessFile file, long start, long end, long timeoutMs, long writeLimit) {
        // 更新全局写限速值
        trafficHandler.setWriteLimit(writeLimit);

        URI uri;
        try {
            URIBuilder uriBuilder = new URIBuilder(url);
            if (params != null) {
                for (Map.Entry<String, String> entry : params.entrySet()) {
                    uriBuilder.setParameter(entry.getKey(), entry.getValue());
                }
            }
            uri = uriBuilder.build();
        } catch (URISyntaxException e) {
            throw new RuntimeException("Invalid upload url: " + url, e);
        }
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            if ("http".equalsIgnoreCase(scheme)) {
                port = 80;
            } else if ("https".equalsIgnoreCase(scheme)) {
                port = 443;
            }
        }
        final int finalPort = port;
        boolean ssl = "https".equalsIgnoreCase(scheme);

        CompletableFuture<String> future = new CompletableFuture<>();
        Channel ch = null;
        boolean transferStarted = false;

        try {
            final SslContext sslCtx;
            if (ssl) {
                sslCtx = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            } else {
                sslCtx = null;
            }

            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        public void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            if (sslCtx != null) {
                                p.addLast(sslCtx.newHandler(ch.alloc(), host, finalPort));
                            }
                            
                            // 关键：添加流量整形处理器
                            p.addLast(trafficHandler);

                            // 单连接流量整形与监控 (用于低速切断)
                            // checkInterval = 1000ms
                            ChannelTrafficShapingHandler channelTrafficHandler = new ChannelTrafficShapingHandler(0, 0, 1000);
                            p.addLast(channelTrafficHandler);

                            // 低速检测: 5秒后开始，每3秒检查一次
                            java.util.concurrent.atomic.AtomicInteger lowSpeedStrikes = new java.util.concurrent.atomic.AtomicInteger(0);
                            ch.eventLoop().scheduleAtFixedRate(() -> {
                                if (!ch.isActive()) return;

                                long speed = channelTrafficHandler.trafficCounter().lastWriteThroughput();
                                // 阈值: 10KB/s
                                if (speed < 10 * 1024) {
                                    if (lowSpeedStrikes.incrementAndGet() >= 2) {
                                        log.info(LogKvs.event("Netty.Upload.LowSpeed")
                                                .add("speed", speed)
                                                .add("channelId", ch.id())
                                                .toString());
                                        ch.close();
                                        future.completeExceptionally(new RuntimeException("Low upload speed: " + speed + " B/s"));
                                    }
                                } else {
                                    lowSpeedStrikes.set(0);
                                }
                            }, 5, 3, TimeUnit.SECONDS);
                            
                            p.addLast(new HttpClientCodec());
                            p.addLast(new HttpObjectAggregator(65536)); // 聚合响应
                            p.addLast(new ChunkedWriteHandler()); // 支持大文件流式写入
                            p.addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
                                    String content = msg.content().toString(io.netty.util.CharsetUtil.UTF_8);
                                    if (msg.status().code() == 200) {
                                        future.complete(content);
                                    } else {
                                        future.completeExceptionally(new RuntimeException("Upload failed: " + msg.status() + ", " + content));
                                    }
                                    ctx.close();
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    future.completeExceptionally(cause);
                                    ctx.close();
                                }
                            });
                        }
                    });

            ch = b.connect(host, finalPort).sync().channel();

            // 构造 HTTP 请求
            String requestUri = uri.getRawPath();
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                requestUri = requestUri + "?" + rawQuery;
            }
            HttpRequest request = new DefaultHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.PUT, requestUri);
            request.headers().set(HttpHeaderNames.HOST, host);
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);

            if (headers != null) {
                headers.forEach((k, v) -> request.headers().set(k, v));
            }

            // 发送请求头
            long length = end - start;
            request.headers().set(HttpHeaderNames.CONTENT_LENGTH, length);
            ch.writeAndFlush(request);

            // 发送文件体 (ChunkedFile)
            // 每次发送 8KB 的 chunk，GlobalTrafficShapingHandler 会在这些 chunk 之间插入延迟
            // 注意：ChunkedFile 构造函数会自动 seek 到 start 位置
            ch.writeAndFlush(new ChunkedFile(file, start, length, 8192));
            transferStarted = true;

            // 等待结果
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);

        } catch (java.util.concurrent.TimeoutException e) {
            log.error("Netty upload timeout", e);
            if (ch != null) {
                ch.close();
            }
            throw new RuntimeException("Netty upload timeout", e);
        } catch (Exception e) {
            log.error("Netty upload failed", e);
            // 如果传输未开始（例如连接失败），需要手动关闭文件
            if (!transferStarted) {
                try {
                    file.close();
                } catch (Exception ignored) {
                }
            }
            // 确保关闭 Channel
            if (ch != null) {
                ch.close();
            }
            throw new RuntimeException("Netty upload failed: " + e.getMessage(), e);
        }
    }
}
