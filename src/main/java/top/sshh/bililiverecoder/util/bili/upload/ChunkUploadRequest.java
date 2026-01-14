package top.sshh.bililiverecoder.util.bili.upload;

import org.apache.http.entity.InputStreamEntity;
import top.sshh.bililiverecoder.util.ShardingInputStream;
import top.sshh.bililiverecoder.util.bili.HttpClientResult;
import top.sshh.bililiverecoder.util.bili.HttpClientUtils;
import top.sshh.bililiverecoder.util.bili.upload.pojo.PreUploadBean;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class ChunkUploadRequest {

    private final String URL;
    private HashMap<String, String> headers = new HashMap<String, String>();
    private PreUploadBean preUploadBean;
    private Map<String, String> params;
    private RandomAccessFile file;


    public ChunkUploadRequest(PreUploadBean preUploadBean, Map<String, String> params, RandomAccessFile file) {
        this.URL = "https:" + preUploadBean.getEndpoint() + preUploadBean.getUpUrl();
        headers.clear();
        headers.put("X-Upos-Auth", preUploadBean.getAuth());
        headers.put("Content-Type", "application/octet-stream");
        this.preUploadBean = preUploadBean;
        this.params = params;
        this.file = file;
    }

    /**
     * 获取预上传的信息
     *
     * @return
     * @throws IOException
     * @throws NoSuchAlgorithmException
     * @throws KeyStoreException
     * @throws URISyntaxException
     * @throws KeyManagementException
     */
    public String getPage() throws IOException, NoSuchAlgorithmException, KeyStoreException, URISyntaxException, KeyManagementException {
        // 使用 Netty 进行平滑限速上传
        if (top.sshh.bililiverecoder.service.RateLimiterService.getInstance() != null) {
            top.sshh.bililiverecoder.service.RateLimiterService rateLimiterService = top.sshh.bililiverecoder.service.RateLimiterService.getInstance();
            double speedLimit = rateLimiterService.getUploadBandwidthLimiter().getRate();
            
            // 如果限速值非常大（例如默认的 Double.MAX_VALUE），则视为不限速，使用原有的 Apache HttpClient (支持连接池，更稳定)
            // 阈值设为 100GB/s，超过此值视为不限速
            if (speedLimit < 100L * 1024 * 1024 * 1024) {
                // 计算超时时间
                int timeoutMs = Integer.parseInt(preUploadBean.getTimeout()) * 1000;
                long chunkSize = Long.parseLong(params.get("size"));
                if (speedLimit > 0) {
                    long expectedTimeMs = (long) ((chunkSize / speedLimit) * 1000);
                    timeoutMs = Math.max(timeoutMs, (int) (expectedTimeMs + 30000));
                }

                try {
                    long start = Long.parseLong(params.get("start"));
                    long end = Long.parseLong(params.get("end"));
                    // 调用 Netty 客户端
                    return top.sshh.bililiverecoder.util.NettyUploadClient.put(
                        URL, headers, params, file, start, end, timeoutMs, (long) speedLimit
                    );
                } catch (Exception e) {
                    throw new RuntimeException("Netty upload failed", e);
                }
            }
        }

        // 备用方案 / 不限速方案 (Apache HttpClient)
        ShardingInputStream inputStream = new ShardingInputStream(file, Long.parseLong(params.get("start")), Long.parseLong(params.get("end")));
        java.io.InputStream finalStream = inputStream;
        
        // 默认超时时间 (毫秒)
        int timeoutMs = Integer.parseInt(preUploadBean.getTimeout()) * 1000;
        
        InputStreamEntity body = new InputStreamEntity(finalStream, Long.parseLong(params.get("size")));
        HttpClientResult result = HttpClientUtils.doPut(URL, headers, params, body, timeoutMs);
        int code = result.getCode();
        if (code != 200) {
            throw new RuntimeException("上传返回http状态码为：" + code + "，数据为 " + result.getContent());
        }
        return result.getContent();
    }
}
