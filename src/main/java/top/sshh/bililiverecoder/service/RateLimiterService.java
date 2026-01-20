package top.sshh.bililiverecoder.service;

import com.google.common.util.concurrent.RateLimiter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.NettyUploadClient;

@Slf4j
@Service
public class RateLimiterService {

    private static RateLimiterService instance;

    // API请求限速 (每秒请求数 QPS) - 默认 5 QPS
    private final RateLimiter apiRateLimiter = RateLimiter.create(5.0);

    // 上传带宽限速 (每秒字节数 Bytes) - 默认 0 (不限速)
    private final RateLimiter uploadBandwidthLimiter = RateLimiter.create(Double.MAX_VALUE);

    @PostConstruct
    public void init() {
        instance = this;
        log.info("[BLR] {}", LogKvs.event("RateLimiter.Init")
                .add("apiLimit", apiRateLimiter.getRate())
                .add("uploadLimit", uploadBandwidthLimiter.getRate()));
    }

    public static RateLimiterService getInstance() {
        return instance;
    }

    public RateLimiter getApiRateLimiter() {
        return apiRateLimiter;
    }

    public RateLimiter getUploadBandwidthLimiter() {
        return uploadBandwidthLimiter;
    }

    public void setApiRateLimit(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            apiRateLimiter.setRate(Double.MAX_VALUE);
        } else {
            apiRateLimiter.setRate(permitsPerSecond);
        }
        log.info("[BLR] {}", LogKvs.event("RateLimiter.Update")
                .add("type", "API")
                .add("newLimit", permitsPerSecond));
    }

    public void setUploadSpeedLimit(double mbPerSecond) {
        if (mbPerSecond <= 0) {
            uploadBandwidthLimiter.setRate(Double.MAX_VALUE);
            // 同时更新 Netty 的全局限速器
            NettyUploadClient.updateWriteLimit(0);
            log.info("[BLR] {}", LogKvs.event("RateLimiter.Update")
                    .add("type", "Upload")
                    .add("newLimitMB", "Unlimited"));
        } else {
            double bytesPerSecond = mbPerSecond * 1024 * 1024;
            uploadBandwidthLimiter.setRate(bytesPerSecond);
            // 同时更新 Netty 的全局限速器
            NettyUploadClient.updateWriteLimit((long) bytesPerSecond);
            log.info("[BLR] {}", LogKvs.event("RateLimiter.Update")
                    .add("type", "Upload")
                    .add("newLimitMB", mbPerSecond));
        }
    }
}
