package top.sshh.bililiverecoder.util.bili.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.HttpClientResult;
import top.sshh.bililiverecoder.util.bili.HttpClientUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultipartInitRequest {

    private static final Logger log = LogManager.getLogger(MultipartInitRequest.class);
    private static final String NEW_URL = "https://member.bilibili.com/upload/multipart/new";
    private static final String LEGACY_URL = "https://member.bilibili.com/x/vupre/web/archive/types/upload";

    private final Cookie cookie;

    public MultipartInitRequest(Cookie cookie) {
        this.cookie = cookie;
    }

    public MultipartInitInfo init(String uploadId,
                                  String filename,
                                  String zipUrl,
                                  String fallbackProfile,
                                  long fallbackBizId,
                                  long fileSize) throws Exception {
        return initByMultipartNew(uploadId, filename, zipUrl, fallbackProfile, fallbackBizId, fileSize);
    }

    private MultipartInitInfo initByMultipartNew(String uploadId,
                                                 String filename,
                                                 String zipUrl,
                                                 String fallbackProfile,
                                                 long fallbackBizId,
                                                 long fileSize) throws Exception {
        HashMap<String, String> headers = new HashMap<>();
        cookie.toHeaderCookie(headers);
        headers.put("referer", "https://member.bilibili.com/platform/upload/video/frame?page_from=creative_home_top_upload");
        headers.put("origin", "https://member.bilibili.com");
        headers.put("Content-Type", "application/json; charset=UTF-8");

        String metaUposUri = deriveMetaUposUri(zipUrl, fallbackProfile);
        JSONObject initParams = new JSONObject(true);
        initParams.put("meta_upos_uri", metaUposUri);

        JSONObject payload = new JSONObject(true);
        payload.put("profile", fallbackProfile);
        payload.put("name", filename);
        payload.put("size", fileSize);
        payload.put("init_params", initParams);

        if (MultipartDebugSupport.isEnabled()) {
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.InitNew.Request")
                    .addIfNotBlank("uploadId", uploadId)
                    .addIfNotBlank("filename", filename)
                    .add("size", fileSize)
                    .addIfNotBlank("profile", fallbackProfile)
                    .addIfNotBlank("zipUrl", abbreviateZipUrl(zipUrl))
                    .addIfNotBlank("metaUposUri", initParams.getString("meta_upos_uri")));
        }

        HttpClientResult result = HttpClientUtils.doPost(
                NEW_URL,
                headers,
                null,
                new StringEntity(payload.toJSONString(), StandardCharsets.UTF_8),
                60 * 1000
        );

        String content = result.getContent();
        JSONObject root = JSON.parseObject(content);
        if (MultipartDebugSupport.isEnabled()) {
            JSONObject debugData = root == null ? null : root.getJSONObject("data");
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.InitNew.Response")
                    .add("httpCode", result.getCode())
                    .add("bizCode", root == null ? null : root.getInteger("code"))
                    .addIfNotBlank("bizMsg", root == null ? null : root.getString("message"))
                    .addIfNotBlank("rootKeys", root == null ? "" : String.join(",", root.keySet()))
                    .addIfNotBlank("dataKeys", debugData == null ? "" : String.join(",", debugData.keySet()))
                    .addIfNotBlank("respSnippet", MultipartDebugSupport.abbreviate(content, 320)));
        }
        if (root == null) {
            throw buildDiagnosticException("multipart new init empty response", result.getCode(), uploadId, filename, zipUrl, null, null, content);
        }

        if (root.containsKey("code") && root.getIntValue("code") != 0) {
            throw buildDiagnosticException("multipart new init failed", result.getCode(), uploadId, filename, zipUrl, root, root.getJSONObject("data"), content);
        }

        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            data = root;
        }

        String resolvedUploadId = firstNotBlank(
                data.getString("upload_id"),
                root.getString("upload_id")
        );
        String uploadToken = firstNotBlank(
                data.getString("upload_token"),
                data.getString("uptoken"),
                root.getString("upload_token"),
                root.getString("uptoken")
        );
        String uri = firstNotBlank(
                data.getString("uri"),
                data.getString("upos_uri"),
                root.getString("uri"),
                root.getString("upos_uri"),
                zipUrl
        );

        String profile = MultipartSessionValidator.preferProfileByUri(
                firstNotBlank(
                        data.getString("profile"),
                        root.getString("profile"),
                        fallbackProfile
                ),
                uri
        );

        long bizId = fallbackBizId;
        if (data.containsKey("biz_id")) {
            bizId = data.getLongValue("biz_id");
        }

        if (StringUtils.isBlank(uploadToken) || StringUtils.isBlank(uri)) {
            throw buildDiagnosticException("multipart new init missing upload_token or uri", result.getCode(), resolvedUploadId, filename, zipUrl, root, data, content);
        }

        long chunkSize = data.getLongValue("chunk_size");
        int threads = data.getIntValue("threads");
        int timeout = data.getIntValue("timeout");

        return new MultipartInitInfo(resolvedUploadId, uploadToken, uri, profile, bizId, metaUposUri, chunkSize, threads, timeout);
    }

    private MultipartInitInfo initByLegacy(String uploadId,
                                           String filename,
                                           String zipUrl,
                                           String fallbackProfile,
                                           long fallbackBizId) throws Exception {
        HashMap<String, String> headers = new HashMap<>();
        cookie.toHeaderCookie(headers);
        headers.put("referer", "https://member.bilibili.com/platform/upload/video/frame?page_from=creative_home_top_upload");
        headers.put("origin", "https://member.bilibili.com");

        Map<String, String> params = new HashMap<>();
        params.put("upload_id", uploadId);
        params.put("filename", filename);
        params.put("zip_url", zipUrl);
        params.put("mobi_app", "");
        params.put("platform", "pc");
        params.put("build", "");
        params.put("device", "");
        params.put("t", String.valueOf(System.currentTimeMillis()));

        if (MultipartDebugSupport.isEnabled()) {
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.InitLegacy.Request")
                    .addIfNotBlank("uploadId", uploadId)
                    .addIfNotBlank("filename", filename)
                    .addIfNotBlank("zipUrl", abbreviateZipUrl(zipUrl)));
        }

        HttpClientResult result = HttpClientUtils.doGet(LEGACY_URL, headers, params);
        String content = result.getContent();
        JSONObject root = JSON.parseObject(content);
        if (MultipartDebugSupport.isEnabled()) {
            JSONObject debugData = root == null ? null : root.getJSONObject("data");
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.InitLegacy.Response")
                    .add("httpCode", result.getCode())
                    .add("bizCode", root == null ? null : root.getInteger("code"))
                    .addIfNotBlank("bizMsg", root == null ? null : root.getString("message"))
                    .addIfNotBlank("rootKeys", root == null ? "" : String.join(",", root.keySet()))
                    .addIfNotBlank("dataKeys", debugData == null ? "" : String.join(",", debugData.keySet()))
                    .addIfNotBlank("respSnippet", MultipartDebugSupport.abbreviate(content, 320)));
        }
        if (root == null) {
            throw buildDiagnosticException("multipart init empty response", result.getCode(), uploadId, filename, zipUrl, null, null, content);
        }

        if (root.containsKey("code") && root.getIntValue("code") != 0) {
            throw buildDiagnosticException("multipart init failed", result.getCode(), uploadId, filename, zipUrl, root, root.getJSONObject("data"), content);
        }

        JSONObject data = root.getJSONObject("data");
        if (data == null && root.containsKey("OK")) {
            data = root;
        }
        if (data == null) {
            data = root;
        }

        String uploadToken = firstNotBlank(
                data.getString("upload_token"),
                data.getString("uptoken")
        );
        String uri = firstNotBlank(
                data.getString("uri"),
                data.getString("upos_uri"),
                zipUrl
        );

        String profile = fallbackProfile;
        String putQuery = data.getString("put_query");
        if (StringUtils.isNotBlank(putQuery)) {
            for (String kv : putQuery.split("&")) {
                String[] pair = kv.split("=", 2);
                if (pair.length == 2 && "profile".equals(pair[0])) {
                    String decoded = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (StringUtils.isNotBlank(decoded)) {
                        profile = decoded;
                    }
                }
            }
        }
        profile = MultipartSessionValidator.preferProfileByUri(profile, uri);

        long bizId = fallbackBizId;
        if (data.containsKey("biz_id")) {
            bizId = data.getLongValue("biz_id");
        }

        if (StringUtils.isBlank(uploadToken) || StringUtils.isBlank(uri)) {
            throw buildDiagnosticException("multipart init missing upload_token or uri", result.getCode(), uploadId, filename, zipUrl, root, data, content);
        }

        long chunkSize = data.getLongValue("chunk_size");
        int threads = data.getIntValue("threads");
        int timeout = data.getIntValue("timeout");

        return new MultipartInitInfo(uploadId, uploadToken, uri, profile, bizId, deriveMetaUposUri(zipUrl, fallbackProfile), chunkSize, threads, timeout);
    }

    private String deriveMetaUposUri(String zipUrl, String profile) {
        if (StringUtils.isBlank(zipUrl)) {
            return "";
        }
        String raw = zipUrl.trim();
        if (!raw.startsWith("upos://")) {
            return raw;
        }
        String bucket = extractUposBucket(raw);
        String profileBucket = deriveBucketByProfile(profile);
        if (StringUtils.isNotBlank(profileBucket)) {
            bucket = profileBucket;
        }
        int slash = raw.lastIndexOf('/');
        String name = slash >= 0 ? raw.substring(slash + 1) : raw.substring("upos://".length());
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return "upos://" + deriveMetaBucket(bucket) + "/" + base + ".txt";
    }

    private String extractUposBucket(String uposUri) {
        String withoutScheme = StringUtils.removeStart(uposUri, "upos://");
        int slash = withoutScheme.indexOf('/');
        return slash >= 0 ? withoutScheme.substring(0, slash) : withoutScheme;
    }

    private String deriveMetaBucket(String bucket) {
        if (StringUtils.isBlank(bucket)) {
            return "fxmetalf";
        }
        String lower = bucket.toLowerCase();
        // ugcfxever -> fxmetalf
        if (lower.startsWith("ugc") && lower.endsWith("ever") && lower.length() > 7) {
            String segment = lower.substring(3, lower.length() - 4);
            if (StringUtils.isNotBlank(segment)) {
                return segment + "metalf";
            }
        }
        // ugcever 场景没有 segment，显式映射到 emetalf
        if ("ugcever".equals(lower)) {
            return "emetalf";
        }
        return "fxmetalf";
    }

    private String deriveBucketByProfile(String profile) {
        String normalized = StringUtils.lowerCase(StringUtils.substringBefore(StringUtils.trimToEmpty(profile), "/"));
        if (StringUtils.isBlank(normalized)) {
            return "";
        }
        if ("ugce".equals(normalized)) {
            return "ugcever";
        }
        if (normalized.startsWith("ugc") && normalized.length() > 3) {
            return normalized + "ever";
        }
        return "";
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private MultipartInitDiagnosticException buildDiagnosticException(String message,
                                                                      int httpCode,
                                                                      String uploadId,
                                                                      String filename,
                                                                      String zipUrl,
                                                                      JSONObject root,
                                                                      JSONObject data,
                                                                      String content) {
        String rootKeys = root == null ? "" : String.join(",", root.keySet());
        String dataKeys = data == null ? "" : String.join(",", data.keySet());
        boolean hasUploadToken = data != null && StringUtils.isNotBlank(data.getString("upload_token"));
        boolean hasUptoken = data != null && StringUtils.isNotBlank(data.getString("uptoken"));
        boolean hasUri = data != null && StringUtils.isNotBlank(data.getString("uri"));
        boolean hasUposUri = data != null && StringUtils.isNotBlank(data.getString("upos_uri"));

        List<String> missing = new ArrayList<>();
        if (!hasUploadToken && !hasUptoken) {
            missing.add("upload_token/uptoken");
        }
        if (!hasUri && !hasUposUri) {
            missing.add("uri/upos_uri");
        }

        String snippet = content == null ? "" : content.replace("\r", " ").replace("\n", " ");
        if (snippet.length() > 600) {
            snippet = snippet.substring(0, 600) + "...";
        }

        return new MultipartInitDiagnosticException(
                message,
                httpCode,
                uploadId,
                filename,
                abbreviateZipUrl(zipUrl),
                root != null ? root.getInteger("code") : null,
                root != null ? root.getString("message") : null,
                rootKeys,
                dataKeys,
                hasUploadToken,
                hasUptoken,
                hasUri,
                hasUposUri,
                String.join(",", missing),
                snippet
        );
    }

    private String abbreviateZipUrl(String zipUrl) {
        if (StringUtils.isBlank(zipUrl)) {
            return "";
        }
        String value = zipUrl;
        if (value.length() > 120) {
            value = value.substring(0, 120) + "...";
        }
        return value;
    }

    public static class MultipartInitDiagnosticException extends RuntimeException {
        private final int httpCode;
        private final String uploadId;
        private final String filename;
        private final String zipUrl;
        private final Integer bizCode;
        private final String bizMessage;
        private final String rootKeys;
        private final String dataKeys;
        private final boolean hasUploadToken;
        private final boolean hasUptoken;
        private final boolean hasUri;
        private final boolean hasUposUri;
        private final String missingFields;
        private final String responseSnippet;

        public MultipartInitDiagnosticException(String message,
                                               int httpCode,
                                               String uploadId,
                                               String filename,
                                               String zipUrl,
                                               Integer bizCode,
                                               String bizMessage,
                                               String rootKeys,
                                               String dataKeys,
                                               boolean hasUploadToken,
                                               boolean hasUptoken,
                                               boolean hasUri,
                                               boolean hasUposUri,
                                               String missingFields,
                                               String responseSnippet) {
            super(message);
            this.httpCode = httpCode;
            this.uploadId = uploadId;
            this.filename = filename;
            this.zipUrl = zipUrl;
            this.bizCode = bizCode;
            this.bizMessage = bizMessage;
            this.rootKeys = rootKeys;
            this.dataKeys = dataKeys;
            this.hasUploadToken = hasUploadToken;
            this.hasUptoken = hasUptoken;
            this.hasUri = hasUri;
            this.hasUposUri = hasUposUri;
            this.missingFields = missingFields;
            this.responseSnippet = responseSnippet;
        }

        public int getHttpCode() {
            return httpCode;
        }

        public String getUploadId() {
            return uploadId;
        }

        public String getFilename() {
            return filename;
        }

        public String getZipUrl() {
            return zipUrl;
        }

        public Integer getBizCode() {
            return bizCode;
        }

        public String getBizMessage() {
            return bizMessage;
        }

        public String getRootKeys() {
            return rootKeys;
        }

        public String getDataKeys() {
            return dataKeys;
        }

        public boolean isHasUploadToken() {
            return hasUploadToken;
        }

        public boolean isHasUptoken() {
            return hasUptoken;
        }

        public boolean isHasUri() {
            return hasUri;
        }

        public boolean isHasUposUri() {
            return hasUposUri;
        }

        public String getMissingFields() {
            return missingFields;
        }

        public String getResponseSnippet() {
            return responseSnippet;
        }
    }

    public static class MultipartInitInfo {
        private final String uploadId;
        private final String uploadToken;
        private final String uri;
        private final String profile;
        private final long bizId;
        private final String metaUposUri;
        private final long chunkSize;
        private final int threads;
        private final int timeout;

        public MultipartInitInfo(String uploadId,
                                 String uploadToken,
                                 String uri,
                                 String profile,
                                 long bizId,
                                 String metaUposUri,
                                 long chunkSize,
                                 int threads,
                                 int timeout) {
            this.uploadId = uploadId;
            this.uploadToken = uploadToken;
            this.uri = uri;
            this.profile = profile;
            this.bizId = bizId;
            this.metaUposUri = metaUposUri;
            this.chunkSize = chunkSize;
            this.threads = threads;
            this.timeout = timeout;
        }

        public String getUploadId() {
            return uploadId;
        }

        public String getUploadToken() {
            return uploadToken;
        }

        public String getUri() {
            return uri;
        }

        public String getProfile() {
            return profile;
        }

        public long getBizId() {
            return bizId;
        }

        public String getMetaUposUri() {
            return metaUposUri;
        }

        public long getChunkSize() {
            return chunkSize;
        }

        public int getThreads() {
            return threads;
        }

        public int getTimeout() {
            return timeout;
        }
    }
}
