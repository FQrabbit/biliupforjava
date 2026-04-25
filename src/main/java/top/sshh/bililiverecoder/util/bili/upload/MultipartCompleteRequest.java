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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultipartCompleteRequest {

    private static final Logger log = LogManager.getLogger(MultipartCompleteRequest.class);
    private static final String URL = "https://member.bilibili.com/upload/multipart/complete";

    private final Cookie cookie;

    public MultipartCompleteRequest(Cookie cookie) {
        this.cookie = cookie;
    }

    public JSONObject complete(String uri,
                               String uploadId,
                               String uploadToken,
                               long bizId,
                               String profile,
                               List<Map<String, Object>> parts) throws Exception {
        JSONObject uploadParams = new JSONObject(true);
        uploadParams.put("biz_id", bizId);
        uploadParams.put("profile", profile);

        JSONObject payload = new JSONObject(true);
        payload.put("uri", uri);
        payload.put("upload_token", uploadToken);
        payload.put("parts", parts);
        payload.put("upload_params", uploadParams);

        if (MultipartDebugSupport.isEnabled()) {
            Object firstPart = parts == null || parts.isEmpty() ? null : parts.get(0);
            Object lastPart = parts == null || parts.isEmpty() ? null : parts.get(parts.size() - 1);
            String firstEtag = "";
            String lastEtag = "";
            if (firstPart instanceof Map) {
                firstEtag = String.valueOf(((Map<?, ?>) firstPart).get("etag"));
            }
            if (lastPart instanceof Map) {
                lastEtag = String.valueOf(((Map<?, ?>) lastPart).get("etag"));
            }
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.Complete.Request")
                    .addIfNotBlank("uri", uri)
                    .addIfNotBlank("diagnosticUploadId", uploadId)
                    .addIfNotBlank("uploadTokenHint", MultipartDebugSupport.tokenHint(uploadToken))
                    .add("bizId", bizId)
                    .addIfNotBlank("profile", profile)
                    .add("partsCount", parts == null ? 0 : parts.size())
                    .add("firstEtagQuoted", StringUtils.startsWith(firstEtag, "\"") && StringUtils.endsWith(firstEtag, "\""))
                    .add("lastEtagQuoted", StringUtils.startsWith(lastEtag, "\"") && StringUtils.endsWith(lastEtag, "\""))
                    .addIfNotBlank("firstPart", firstPart == null ? "" : String.valueOf(firstPart))
                    .addIfNotBlank("lastPart", lastPart == null ? "" : String.valueOf(lastPart)));
        }

        HashMap<String, String> headers = new HashMap<>();
        headers.put("accept", "*/*");
        headers.put("Content-Type", "application/json; charset=UTF-8");
        headers.put("origin", "https://member.bilibili.com");
        headers.put("referer", "https://member.bilibili.com/platform/upload/video/frame?page_from=creative_home_top_upload");
        cookie.toHeaderCookie(headers);

        HttpClientResult result = HttpClientUtils.doPost(
                URL,
                headers,
                null,
                new StringEntity(payload.toJSONString(), StandardCharsets.UTF_8),
                120 * 1000
        );
        String content = result.getContent();
        JSONObject root = JSON.parseObject(content);
        if (MultipartDebugSupport.isEnabled()) {
            JSONObject data = root == null ? null : root.getJSONObject("data");
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.Complete.Response")
                    .add("httpCode", result.getCode())
                    .add("bizCode", root == null ? null : root.getInteger("code"))
                    .addIfNotBlank("bizMsg", root == null ? null : root.getString("message"))
                    .addIfNotBlank("dataEtag", data == null ? null : data.getString("etag"))
                    .addIfNotBlank("respSnippet", MultipartDebugSupport.abbreviate(content, 320)));
        }
        return root;
    }

    public static boolean isSuccess(JSONObject response) {
        return response != null && response.getIntValue("code") == 0;
    }
}
