package top.sshh.bililiverecoder.util.bili.upload;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.HttpClientResult;
import top.sshh.bililiverecoder.util.bili.HttpClientUtils;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;

public class MultipartPartRequest {

    private static final Logger log = LogManager.getLogger(MultipartPartRequest.class);
    private static final String URL = "https://member.bilibili.com/upload/multipart/part";

    private final Cookie cookie;

    public MultipartPartRequest(Cookie cookie) {
        this.cookie = cookie;
    }

    public MultipartSignedReq getSignedReq(String uploadId,
                                           String uri,
                                           String uploadToken,
                                           int partNumber,
                                           String expectedCdn) throws Exception {
        JSONObject payload = new JSONObject(true);
        payload.put("uri", uri);
        payload.put("upload_token", uploadToken);
        payload.put("part_number", partNumber);

        if (MultipartDebugSupport.isEnabled()) {
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.Part.Request")
                    .addIfNotBlank("uploadId", uploadId)
                    .addIfNotBlank("uri", uri)
                    .addIfNotBlank("uploadTokenHint", MultipartDebugSupport.tokenHint(uploadToken))
                    .add("partNumber", partNumber)
                    .addIfNotBlank("expectedCdn", expectedCdn));
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
                60 * 1000
        );
        String content = result.getContent();
        JSONObject root = JSON.parseObject(content);
        if (MultipartDebugSupport.isEnabled()) {
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.Part.Response")
                    .add("httpCode", result.getCode())
                    .add("bizCode", root == null ? null : root.getInteger("code"))
                    .addIfNotBlank("bizMsg", root == null ? null : root.getString("message"))
                    .addIfNotBlank("respSnippet", MultipartDebugSupport.abbreviate(content, 320)));
        }
        if (root == null || root.getIntValue("code") != 0) {
            throw new RuntimeException("multipart part request failed: " + content);
        }

        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            throw new RuntimeException("multipart part response missing data: " + content);
        }
        JSONArray reqs = data.getJSONArray("reqs");
        if (reqs == null || reqs.isEmpty()) {
            throw new RuntimeException("multipart part response missing reqs: " + content);
        }

        JSONObject selected = pickBestReq(reqs, expectedCdn);
        String method = selected.getString("method");
        String url = selected.getString("url");
        String cdnName = selected.getString("cdn_name");
        if (!"PUT".equalsIgnoreCase(method) || StringUtils.isBlank(url)) {
            throw new RuntimeException("multipart part response invalid req: " + selected);
        }
        int partNumberFromUrl = MultipartDebugSupport.partNumberFromUrl(url);
        if (partNumberFromUrl > 0 && partNumberFromUrl != partNumber) {
            throw new RuntimeException("multipart part response part_number mismatch, expected="
                    + partNumber + ", actual=" + partNumberFromUrl + ", req=" + selected);
        }
        if (MultipartDebugSupport.isEnabled()) {
            List<String> cdnNames = new ArrayList<>(reqs.size());
            for (int i = 0; i < reqs.size(); i++) {
                JSONObject req = reqs.getJSONObject(i);
                if (req != null && StringUtils.isNotBlank(req.getString("cdn_name"))) {
                    cdnNames.add(req.getString("cdn_name"));
                }
            }
            int selectedIndex = findSelectedIndex(reqs, url);
            log.info("[BLR] {}", LogKvs.event("Upload.MultipartDebug.Part.SelectedReq")
                    .add("partNumber", partNumber)
                    .add("reqCount", reqs.size())
                    .add("selectedIndex", selectedIndex)
                    .addIfNotBlank("selectedCdn", cdnName)
                    .addIfNotBlank("selectedHost", MultipartDebugSupport.hostOfUrl(url))
                    .addIfNotBlank("selectedUploadId", MultipartDebugSupport.uploadIdFromUrl(url))
                    .add("selectedPartNumberFromUrl", partNumberFromUrl)
                    .addIfNotBlank("cdnNames", String.join(",", cdnNames)));
        }
        return new MultipartSignedReq(url, cdnName);
    }

    private JSONObject pickBestReq(JSONArray reqs, String expectedCdn) {
        for (int i = 0; i < reqs.size(); i++) {
            JSONObject req = reqs.getJSONObject(i);
            if (req == null) {
                continue;
            }
            String method = StringUtils.defaultString(req.getString("method"));
            String url = StringUtils.defaultString(req.getString("url"));
            if ("PUT".equalsIgnoreCase(method) && StringUtils.isNotBlank(url)) {
                if (MultipartDebugSupport.isEnabled() && i > 0) {
                    log.warn("[BLR] {}", LogKvs.event("Upload.MultipartDebug.Part.FirstValidNotFirst")
                            .addIfNotBlank("expectedCdn", expectedCdn)
                            .add("selectedIndex", i)
                            .addIfNotBlank("selectedCdn", req.getString("cdn_name"))
                            .addIfNotBlank("selectedHost", MultipartDebugSupport.hostOfUrl(url)));
                }
                return req;
            }
        }

        throw new RuntimeException("multipart part response contains no usable PUT req: " + reqs);
    }

    private int findSelectedIndex(JSONArray reqs, String selectedUrl) {
        if (reqs == null || reqs.isEmpty() || StringUtils.isBlank(selectedUrl)) {
            return -1;
        }
        for (int i = 0; i < reqs.size(); i++) {
            JSONObject req = reqs.getJSONObject(i);
            if (req != null && selectedUrl.equals(req.getString("url"))) {
                return i;
            }
        }
        return -1;
    }

    public static class MultipartSignedReq {
        private final String url;
        private final String cdnName;

        public MultipartSignedReq(String url, String cdnName) {
            this.url = url;
            this.cdnName = cdnName;
        }

        public String getUrl() {
            return url;
        }

        public String getCdnName() {
            return cdnName;
        }
    }
}
