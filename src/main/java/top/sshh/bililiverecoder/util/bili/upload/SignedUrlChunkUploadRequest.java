package top.sshh.bililiverecoder.util.bili.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.entity.InputStreamEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.sshh.bililiverecoder.service.RateLimiterService;
import top.sshh.bililiverecoder.service.UploadConnectionBudgetService;
import top.sshh.bililiverecoder.service.UploadFairShareService;
import top.sshh.bililiverecoder.util.ShardingInputStream;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.NettyUploadClient;
import top.sshh.bililiverecoder.util.bili.HttpClientResult;
import top.sshh.bililiverecoder.util.bili.HttpClientUtils;

import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.function.BooleanSupplier;

public class SignedUrlChunkUploadRequest {

    private static final Logger log = LogManager.getLogger(SignedUrlChunkUploadRequest.class);

    public String upload(String signedUrl,
                         RandomAccessFile file,
                         long start,
                         long end,
        int timeoutSeconds) throws Exception {
        return upload(signedUrl, file, start, end, timeoutSeconds, null);
    }

    public String upload(String signedUrl,
                         RandomAccessFile file,
                         long start,
                         long end,
                         int timeoutSeconds,
                         BooleanSupplier cancelledSupplier) throws Exception {
        HashMap<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Origin", "https://member.bilibili.com");
        headers.put("Referer", "https://member.bilibili.com/");

        long size = end - start;
        if (size <= 0) {
            throw new IllegalArgumentException("invalid chunk size: " + size);
        }

        int timeoutMs = Math.max(timeoutSeconds, 120) * 1000;
        int code;
        String content;
        String etagHeader = null;
        boolean bandwidthLimited = false;

        UploadConnectionBudgetService budget = UploadConnectionBudgetService.getInstance();
        boolean acquired = false;
        if (budget != null) {
            try {
                budget.acquire();
                acquired = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Upload interrupted while waiting for connection permit", e);
            }
        }
        try {
            long speedLimit = resolveUploadSpeedLimit();
            if (speedLimit > 0) {
                bandwidthLimited = true;
                NettyUploadClient.UploadResponse response = NettyUploadClient.putForResponse(
                        signedUrl, headers, null, file, start, end, timeoutMs, speedLimit, cancelledSupplier);
                code = response.getStatusCode();
                content = response.getContent();
                etagHeader = StringUtils.defaultIfBlank(response.getHeader("ETag"), response.getHeader("etag"));
            } else {
                ShardingInputStream inputStream = new ShardingInputStream(file, start, end, cancelledSupplier);
                InputStreamEntity body = new InputStreamEntity(inputStream, size);
                HttpClientResult result = HttpClientUtils.doPut(signedUrl, headers, null, body, timeoutMs);
                code = result.getCode();
                content = result.getContent();
                if (result.getResponse() != null) {
                    Header etag = result.getResponse().getFirstHeader("ETag");
                    if (etag == null) {
                        etag = result.getResponse().getFirstHeader("etag");
                    }
                    if (etag != null) {
                        etagHeader = etag.getValue();
                    }
                }
            }
        } finally {
            if (acquired) {
                budget.release();
            }
        }

        if (MultipartDebugSupport.isEnabled()) {
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.SignedPut.Response")
                    .addIfNotBlank("host", MultipartDebugSupport.hostOfUrl(signedUrl))
                    .addIfNotBlank("path", MultipartDebugSupport.pathOfUrl(signedUrl))
                    .addIfNotBlank("uploadId", MultipartDebugSupport.uploadIdFromUrl(signedUrl))
                    .add("partNumber", MultipartDebugSupport.partNumberFromUrl(signedUrl))
                    .add("start", start)
                    .add("end", end)
                    .add("size", size)
                    .add("timeoutMs", timeoutMs)
                    .add("httpCode", code)
                    .add("bandwidthLimited", bandwidthLimited)
                    .addIfNotBlank("etagHeader", etagHeader)
                    .addIfNotBlank("respSnippet", MultipartDebugSupport.abbreviate(content, 220)));
        }
        if (code != 200) {
            throw new RuntimeException("signed chunk upload failed, code=" + code + ", content=" + content);
        }

        if (StringUtils.isNotBlank(etagHeader)) {
            return normalizeEtag(etagHeader);
        }

        if (StringUtils.isNotBlank(content)) {
            String c = content.trim();
            if (c.startsWith("{")) {
                JSONObject json = JSON.parseObject(c);
                if (json != null && json.getJSONObject("data") != null) {
                    String etag = json.getJSONObject("data").getString("etag");
                    if (StringUtils.isNotBlank(etag)) {
                        return normalizeEtag(etag);
                    }
                }
            }
        }

        throw new RuntimeException("signed chunk upload missing etag");
    }

    private long resolveUploadSpeedLimit() {
        RateLimiterService rateLimiterService = RateLimiterService.getInstance();
        if (rateLimiterService == null) {
            return 0L;
        }
        long configuredLimit = rateLimiterService.getUploadSpeedLimitBytesPerSecond();
        if (configuredLimit <= 0) {
            return 0L;
        }
        UploadFairShareService fairShareService = UploadFairShareService.getInstance();
        if (fairShareService == null) {
            return configuredLimit;
        }
        return fairShareService.fairShareLimit(configuredLimit);
    }

    private String normalizeEtag(String etag) {
        String normalized = StringUtils.trimToEmpty(etag);
        if (StringUtils.isBlank(normalized)) {
            return normalized;
        }
        if (normalized.startsWith("\"") && normalized.endsWith("\"")) {
            return normalized;
        }
        return "\"" + normalized.replace("\"", "") + "\"";
    }
}
