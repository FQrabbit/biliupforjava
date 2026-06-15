package top.sshh.bililiverecoder.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.LiveMsg;
import top.sshh.bililiverecoder.entity.data.*;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;

import javax.crypto.Cipher;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
public class BiliApi {

    // TODO: 从配置文件读取
    private static String appKey = "4409e2ce8ffd12b8";
    private static String appSecret = "59b43e04ad6965f34319062b478f83dd";

    public static void setAppKey(String key) {
        appKey = key;
    }

    public static void setAppSecret(String secret) {
        appSecret = secret;
    }

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int[] WBI_MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };
    private static volatile WbiKeyCache wbiKeyCache;
    private static String BUVID;
    private static String DEVICE_ID;

    static {
        java.util.Properties props = new java.util.Properties();
        java.io.File file = new java.io.File("device.properties");
        try {
            if (file.exists()) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                    props.load(in);
                }
            }

            BUVID = props.getProperty("buvid");
            DEVICE_ID = props.getProperty("deviceId");

            boolean save = false;
            if (StringUtils.isBlank(BUVID)) {
                BUVID = "XX" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
                props.setProperty("buvid", BUVID);
                save = true;
            }
            if (StringUtils.isBlank(DEVICE_ID)) {
                DEVICE_ID = UUID.randomUUID().toString().replace("-", "");
                props.setProperty("deviceId", DEVICE_ID);
                save = true;
            }

            if (save) {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    props.store(out, "Bilibili Device IDs");
                }
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("BiliApi.DeviceIds.LoadOrSaveFailed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            if (StringUtils.isBlank(BUVID)) BUVID = "XX" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
            if (StringUtils.isBlank(DEVICE_ID)) DEVICE_ID = UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static Map<String, String> getCommonHeaders() {
        Map<String, String> headers = new HashMap<>();
        long currentSecond = Instant.now().getEpochSecond();
        headers.put("Display-ID", BUVID + "-" + currentSecond);
        headers.put("Buvid", BUVID);
        headers.put("User-Agent", USER_AGENT);
        headers.put("Device-ID", DEVICE_ID);
        return headers;
    }


    public static String getLoginKey() {
        String url = "https://passport.bilibili.com/api/oauth2/getKey";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", appKey);
        params.put("build", "101800");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        params.put("sign", sign(params, appSecret));
        Map<String, String> headers = getCommonHeaders();
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        return HttpClientUtil.post(url, headers, params, true);
    }


    public static String sign(Map<String, String> params, String appSecret) {
        // 签名规则： md5(url编码后的请求参数（body）)
        String body = params.entrySet().stream()
                .map(e -> {
                    try {
                        return e.getKey() + "=" + URLEncoder.encode(e.getValue(), String.valueOf(StandardCharsets.UTF_8));
                    } catch (UnsupportedEncodingException ex) {
                        log.warn("[BLR] {}", LogKvs.event("BiliApi.Sign.UrlEncodeFailed")
                                .add("key", e.getKey())
                                .add("valueLen", e.getValue() == null ? 0 : e.getValue().length())
                                .addIfNotBlank("err", ex.getMessage())
                                .add("ex", ex.getClass().getSimpleName()), ex);
                        // 理论上 UTF-8 不会失败；兜底返回空值避免 NPE
                        return e.getKey() + "=";
                    }
                })
                .collect(Collectors.joining("&"));
        return DigestUtils.md5Hex(body + appSecret);
    }

    public static String rsa(String str, String key) {
        try {
            key = key
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("\n", "")
                    .replace("-----END PUBLIC KEY-----", "");
            byte[] decode = Base64.getDecoder().decode(key);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(decode);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);
            Cipher encryptCipher = Cipher.getInstance("RSA");
            encryptCipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] secretMessageBytes = str.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedMessageBytes = encryptCipher.doFinal(secretMessageBytes);
            return Base64.getEncoder().encodeToString(encryptedMessageBytes);
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("BiliApi.Rsa.Failed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new RuntimeException(e.getMessage());
        }
    }

    public static String preUpload(BiliBiliUser user, String profile) {
        String url = "https://member.bilibili.com/preupload";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", appKey);
        params.put("access_key", user.getAccessToken());
        params.put("build", "2100400");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        params.put("sign", sign(params, appSecret));

        params.put("profile", profile);
        params.put("mid", user.getUid().toString());

        Map<String, String> headers = getCommonHeaders();
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        return HttpClientUtil.get(uriBuilder.toUriString(), headers);
    }

    public static String preUpload(BiliBiliUser user, Map<String, String> param) {
        String url = "https://member.bilibili.com/preupload";
        Map<String, String> params = new TreeMap<>();
        // params.put("appkey", appKey);
        // params.put("access_key", user.getAccessToken());
        params.put("build", "2100400");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        // params.put("sign", sign(params, appSecret));
        params.putAll(param);

        Map<String, String> headers = getCommonHeaders();

        headers.put("cookie", user.getCookies());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        return HttpClientUtil.get(uriBuilder.toUriString(), headers);
    }


    public static String webPublish(BiliBiliUser user, VideoUploadDto data) {
        return webPublish(user, data, null);
    }

    public static String webPublish(BiliBiliUser user, VideoUploadDto data, Map<String, String> captcha) {
        WebCookie cookie = Cookie.parse(user.getCookies());
        long nowMs = System.currentTimeMillis();
        String nowSeconds = String.valueOf(nowMs / 1000);
        Map<String, String> query = new TreeMap<>();
        query.put("csrf", cookie.getCsrf());
        query.put("t", String.valueOf(nowMs));
        query.put("web_location", "333.1024");
        query.put("wts", nowSeconds);
        query.put("w_rid", signWbi(query, getWbiMixinKey(cookie)));
        String url = "https://member.bilibili.com/x/vu/web/add/v3?" + toQueryString(query);
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "*/*");
        headers.put("Origin", "https://member.bilibili.com");
        headers.put("Referer", "https://member.bilibili.com/platform/upload/video/frame?page_from=creative_home_top_upload");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("X-Event-TraceID", buildWebEventTraceId(cookie));
        headers.put("cookie", buildWebCookieHeader(cookie));
        com.alibaba.fastjson.JSONObject jsonObject = com.alibaba.fastjson.JSONObject.parseObject(JSON.toJSONString(data));
        jsonObject.remove("csrf");
        jsonObject.putIfAbsent("act_reserve_create_title", "");
        jsonObject.putIfAbsent("ai_cover", 0);
        jsonObject.putIfAbsent("is_ab_cover", 0);
        jsonObject.put("ab_cover_info", null);
        jsonObject.putIfAbsent("neutral_mark", "");
        jsonObject.putIfAbsent("space_hidden", 2);
        jsonObject.putIfAbsent("subtitle", com.alibaba.fastjson.JSONObject.parseObject("{\"open\":0,\"lan\":\"\"}"));
        jsonObject.putIfAbsent("watermark", com.alibaba.fastjson.JSONObject.parseObject("{\"state\":0}"));
        normalizeWebPublishBody(jsonObject);
        if (captcha != null) {
            jsonObject.putAll(captcha);
        }
        String body = jsonObject.toJSONString();
        log.info("[BLR] {}", LogKvs.event("BiliApi.WebPublish.Request")
                .add("hasWbi", true)
                .add("wts", nowSeconds)
                .add("wRidHint", tokenHint(query.get("w_rid")))
                .add("webLocation", query.get("web_location"))
                .add("bodyLen", body.length())
                .add("bodyKeys", String.join(",", jsonObject.keySet()))
                .add("videoCount", data.getVideos() == null ? 0 : data.getVideos().size()));
        return HttpClientUtil.post(url, headers, body);
    }


    // public static String clientPublish(String accessToken, VideoUploadDto data) {
    //     String url = "https://member.bilibili.com/x/vu/client/add?access_key=" + accessToken;
    //     Map<String, String> query = new HashMap<>();
    //     query.put("access_key", accessToken);
    //     String sign = sign(query, appSecret);
    //     url = url + "&sign=" + sign;
    //     Map<String, String> headers = new HashMap<>();
    //     long currentSecond = Instant.now().getEpochSecond();
    //     headers.put("Display-ID", "XXD9E43D7A1EBB6669597650E3EE417D9E7F5-" + currentSecond);
    //     headers.put("Buvid", "XXD9E43D7A1EBB6669597650E3EE417D9E7F5");
    //     headers.put("User-Agent", "Mozilla/5.0 BiliDroid/5.37.0 (bbcallen@gmail.com)");
    //     headers.put("Device-ID", "aBRoDWAVeRhsA3FDewMzS3lLMwM");
    //
    //     String body = JSON.toJSONString(data);
    //     return HttpClientUtil.post(url, headers, body);
    // }

    public static String editPublish(BiliBiliUser user, VideoEditUploadDto data) {
        WebCookie cookie = Cookie.parse(user.getCookies());
        String url = "https://member.bilibili.com/x/vu/web/edit?t=" + System.currentTimeMillis() + "&csrf=" + cookie.getCsrf();
        Map<String, String> headers = getCommonHeaders();
        data.setCsrf(cookie.getCsrf());
        headers.put("cookie", cookie.getCookie());
        String body = JSON.toJSONString(data);
        return HttpClientUtil.post(url, headers, body);
    }

    public static String updateVideoVisibility(BiliBiliUser user, long aid, int isOnlySelf) {
        WebCookie cookie = Cookie.parse(user.getCookies());
        String url = "https://member.bilibili.com/x/vu/web/edit/visibility";
        Map<String, String> headers = getCommonHeaders();
        headers.put("cookie", cookie.getCookie());
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        
        Map<String, String> params = new HashMap<>();
        params.put("aid", String.valueOf(aid));
        params.put("is_only_self", String.valueOf(isOnlySelf));
        params.put("csrf", cookie.getCsrf());
        
        return HttpClientUtil.post(url, headers, params, false);
    }

    public static String uploadCover(BiliBiliUser user, String fileName, byte[] fileBytes) {
        WebCookie cookie = Cookie.parse(user.getCookies());
        String url = "https://member.bilibili.com/x/vu/web/cover/up?t=" + System.currentTimeMillis() + "&csrf=" + cookie.getCsrf();

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        BiliResponseDto<BillBuvId> buvId = getBuvId();
        headers.put("cookie", cookie.getCookie() + "buvid3=" + buvId.getData().getB3() + ";buvid4=" + buvId.getData().getB4());
        RequestBody body = new FormBody.Builder().add("cover", "data:image/png;base64," + new String(org.apache.commons.codec.binary.Base64.encodeBase64(fileBytes))).build();
        return HttpClientUtil.post(url, headers,body);
    }

    public static String uploadChunk(
            String uploadUrl,
            String fileName,
            RandomAccessFile r, long size, int nowChunk,
            int chunkNum) throws IOException {

        long allLength = r.length();
        long start = (nowChunk - 1) * size;
        if (start + size > allLength) {
            size = allLength - start;
        }
        ShardingInputStream shardingInputStream = new ShardingInputStream(r, start, size);
        String md5 = DigestUtils.md5Hex(shardingInputStream);
        shardingInputStream.reset();
        ChunkUploadRequestBody chunkUploadRequestBody = new ChunkUploadRequestBody(shardingInputStream);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("version", "2.0.0.1054");
        params.put("filesize", "" + size);
        params.put("chunk", "" + nowChunk);
        params.put("chunks", "" + chunkNum);
        params.put("md5", md5);
        params.put("file", chunkUploadRequestBody);
        Map<String, String> headers = new HashMap<>();
        headers.put("Cookie", "PHPSESSID=" + fileName);
        return HttpClientUtil.upload(uploadUrl, headers, params);
    }

    public static String completeUpload(String url, Integer chunks,
                                        Long filesize,
                                        String md5,
                                        String name,
                                        String version) {
        Map<String, String> params = new HashMap<>();
        params.put("chunks", "" + chunks);
        params.put("filesize", "" + filesize);
        params.put("md5", "" + md5);
        params.put("name", "" + name);
        params.put("version", "" + version);
        return HttpClientUtil.post(url, new HashMap<>(), params, true);

    }

    public static String appMyInfo(BiliBiliUser user) {
        String url = "https://api.bilibili.com/x/member/web/account";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", appKey);
        params.put("access_key", user.getAccessToken());
        params.put("build", "101800");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        params.put("sign", sign(params, appSecret));
        Map<String, String> headers = getCommonHeaders();
        headers.put("cookie", user.getCookies());
        headers.put("x-bili-aurora-eid", "UlMFQVcABlAH");
        headers.put("x-bili-aurora-zone", "sh001");
        headers.put("app-key", "android64");
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        return HttpClientUtil.get(uriBuilder.toUriString(), headers);
    }

    /**
     * 加入合集
     *
     * @param user
     * @return
     */
    public static String addSeasons(BiliBiliUser user, long sectionId, String aid, String cid, String title) {
        WebCookie cookie = Cookie.parse(user.getCookies());
        String url = "https://member.bilibili.com/x2/creative/web/season/section/episodes/add?t=" + System.currentTimeMillis() + "&csrf=" + cookie.getCsrf();
        Map<String, Object> params = new TreeMap<>();
        params.put("csrf", cookie.getCsrf());
        params.put("sectionId", sectionId);
        Map<String, Object> episodes = new HashMap<>();
        episodes.put("aid", Long.valueOf(aid));
        episodes.put("cid", Long.valueOf(cid));
        episodes.put("title", title);
        episodes.put("charging_pay", 0);
        params.put("episodes", Collections.singletonList(episodes));
        Map<String, String> headers = new HashMap<>();
        headers.put("referer", "https://member.bilibili.com/platform/upload/video/frame?page_from=creative_home_top_upload");
        headers.put("user-agent", USER_AGENT);
        BiliResponseDto<BillBuvId> buvId = getBuvId();
        headers.put("cookie", cookie.getCookie() + "buvid3=" + buvId.getData().getB3() + ";buvid4=" + buvId.getData().getB4());
        return HttpClientUtil.postJson(url, headers, params, true);
    }

    public static String getSeasons(BiliBiliUser user) {
        WebCookie cookie = Cookie.parse(user.getCookies());
        String url = "https://member.bilibili.com/x2/creative/web/seasons?pn=1&ps=50";
        Map<String, String> headers = new HashMap<>();
        headers.put("referer", "https://member.bilibili.com/platform/upload/video/frame?page_from=creative_home_top_upload");
        headers.put("user-agent", USER_AGENT);
        BiliResponseDto<BillBuvId> buvId = getBuvId();
        headers.put("cookie", cookie.getCookie() + "buvid3=" + buvId.getData().getB3() + ";buvid4=" + buvId.getData().getB4());
        return HttpClientUtil.get(url, headers);
    }

    public static BiliVideoInfoResponse getVideoInfo(BiliBiliUser user,String bvid) {
        String url = "https://api.bilibili.com/x/web-interface/view";
        Map<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        Map<String, String> headers = getCommonHeaders();
        if(user != null){
            WebCookie cookie = Cookie.parse(user.getCookies());
            BiliResponseDto<BillBuvId> buvId = getBuvId();
            headers.put("cookie", cookie.getCookie() + "buvid3=" + buvId.getData().getB3() + ";buvid4=" + buvId.getData().getB4());
        }
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        String response = HttpClientUtil.get(uriBuilder.toUriString(), headers);
        return JSON.parseObject(response, BiliVideoInfoResponse.class);
    }

    public static BiliLiveRoomInfoResponse getLiveRoomInfo(String roomId) {
        String url = "https://api.live.bilibili.com/room/v1/Room/get_info";
        Map<String, String> params = new TreeMap<>();
        params.put("room_id", roomId);
        Map<String, String> headers = getCommonHeaders();
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        String response = HttpClientUtil.get(uriBuilder.toUriString(), headers);
        return JSON.parseObject(response, BiliLiveRoomInfoResponse.class);
    }

    public static String getLiveGiftConfig(String roomId) {
        String url = "https://api.live.bilibili.com/xlive/web-room/v1/giftPanel/giftConfig";
        Map<String, String> params = new TreeMap<>();
        params.put("platform", "pc");
        params.put("room_id", roomId);
        Map<String, String> headers = getCommonHeaders();
        headers.put("Referer", "https://live.bilibili.com/" + roomId);
        headers.put("Accept", "application/json, text/plain, */*");
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        return HttpClientUtil.get(uriBuilder.toUriString(), headers);
    }

    public static BiliLiveMasterInfoResponse getLiveMasterInfo(long uid) {
        Map<String, String> headers = getCommonHeaders();
        String url = "https://api.live.bilibili.com/live_user/v1/Master/info?uid=" + uid;
        String res = HttpClientUtil.get(url, headers);
        return JSON.parseObject(res, new TypeReference<BiliLiveMasterInfoResponse>() {
        });
    }

    public static BiliVideoPartInfoResponse getVideoPartInfo(BiliBiliUser user, String bvid) {
        return getVideoPartInfoDebug(user, bvid).getParsed();
    }

    public static ApiDebugResponse<BiliVideoPartInfoResponse> getVideoPartInfoDebug(BiliBiliUser user, String bvid) {
        String url = "https://member.bilibili.com/x/vupre/web/archive/view";
        Map<String, String> params = new TreeMap<>();
        params.put("topic_grey", "1");
        params.put("bvid", bvid);
        params.put("t", String.valueOf(System.currentTimeMillis()));
        Map<String, String> headers = getCommonHeaders();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("cache-control", "no-cache");
        headers.put("pragma", "no-cache");
        headers.put("origin", "https://member.bilibili.com");
        headers.put("referer", "https://member.bilibili.com/platform/upload-manager/archive-process?bvid=" + bvid);
        headers.put("sec-fetch-site", "same-origin");
        WebCookie cookie = Cookie.parse(user.getCookies());
        headers.put("cookie", cookie.getCookie());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        String requestUrl = uriBuilder.toUriString();
        String response = HttpClientUtil.get(requestUrl, headers);
        BiliVideoPartInfoResponse parsed = JSON.parseObject(response, BiliVideoPartInfoResponse.class);
        return new ApiDebugResponse<>(parsed, response, requestUrl);
    }

    public static BiliVideoAuditDetailResponse getVideoAuditDetail(BiliBiliUser user, String bvid) {
        return getVideoAuditDetailDebug(user, bvid).getParsed();
    }

    public static ApiDebugResponse<BiliVideoAuditDetailResponse> getVideoAuditDetailDebug(BiliBiliUser user, String bvid) {
        String url = "https://member.bilibili.com/x/web/detail/audit";
        Map<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("web_location", "0.0");
        Map<String, String> headers = getCommonHeaders();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("cache-control", "no-cache");
        headers.put("pragma", "no-cache");
        headers.put("origin", "https://member.bilibili.com");
        headers.put("referer", "https://member.bilibili.com/platform/upload-manager/archive-process?bvid=" + bvid);
        headers.put("sec-fetch-site", "same-origin");
        WebCookie cookie = Cookie.parse(user.getCookies());
        headers.put("cookie", cookie.getCookie());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        String requestUrl = uriBuilder.toUriString();
        String response = HttpClientUtil.get(requestUrl, headers);
        BiliVideoAuditDetailResponse parsed = JSON.parseObject(response, BiliVideoAuditDetailResponse.class);
        return new ApiDebugResponse<>(parsed, response, requestUrl);
    }

    public static ApiDebugResponse<JSONObject> getArchiveProcessVideosDebug(BiliBiliUser user, String bvid, int mode) {
        return getArchiveProcessVideosDebug(user, bvid, mode, null, null);
    }

    public static ApiDebugResponse<JSONObject> getArchiveProcessVideosDebug(BiliBiliUser user, String bvid, int mode, Integer ps, Integer pn) {
        String url = "https://member.bilibili.com/x/web/detail/videos";
        Map<String, String> params = new TreeMap<>();
        params.put("mode", String.valueOf(mode));
        params.put("bvid", bvid);
        if (ps != null) {
            params.put("ps", String.valueOf(ps));
        }
        if (pn != null) {
            params.put("pn", String.valueOf(pn));
        }
        params.put("web_location", "0.0");
        return getArchiveProcessJsonDebug(user, bvid, url, params);
    }

    public static ApiDebugResponse<JSONObject> getArchiveProcessXcodeDebug(BiliBiliUser user, String bvid) {
        return getArchiveProcessXcodeDebug(user, bvid, null);
    }

    public static ApiDebugResponse<JSONObject> getArchiveProcessXcodeDebug(BiliBiliUser user, String bvid, String cid) {
        String url = "https://member.bilibili.com/x/web/detail/xcode";
        Map<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        if (StringUtils.isNotBlank(cid)) {
            params.put("cid", cid);
        }
        params.put("web_location", "0.0");
        return getArchiveProcessJsonDebug(user, bvid, url, params);
    }

    public static ApiDebugResponse<JSONObject> getArchiveProcessArchiveDebug(BiliBiliUser user, String bvid) {
        String url = "https://member.bilibili.com/x/web/detail/archive";
        Map<String, String> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("web_location", "0.0");
        return getArchiveProcessJsonDebug(user, bvid, url, params);
    }

    private static ApiDebugResponse<JSONObject> getArchiveProcessJsonDebug(BiliBiliUser user, String bvid, String url, Map<String, String> params) {
        Map<String, String> headers = getCommonHeaders();
        headers.put("accept", "*/*");
        headers.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headers.put("cache-control", "no-cache");
        headers.put("pragma", "no-cache");
        headers.put("referer", "https://member.bilibili.com/platform/upload-manager/archive-process?bvid=" + bvid);
        headers.put("sec-fetch-site", "same-origin");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-dest", "empty");
        WebCookie cookie = Cookie.parse(user.getCookies());
        headers.put("cookie", cookie.getCookie());
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(url);
        params.forEach(uriBuilder::queryParam);
        String requestUrl = uriBuilder.toUriString();
        String response = HttpClientUtil.get(requestUrl, headers);
        JSONObject parsed;
        try {
            parsed = JSON.parseObject(response);
        } catch (Exception e) {
            parsed = new JSONObject();
            parsed.put("code", -1);
            parsed.put("message", "接口未返回可解析的 JSON：" + e.getMessage());
            parsed.put("parseError", true);
        }
        return new ApiDebugResponse<>(parsed, response, requestUrl);
    }

    @Data
    public static class ApiDebugResponse<T> {
        private final T parsed;
        private final String raw;
        private final String requestUrl;
    }

    public static BiliDmResponse sendVideoDm(BiliBiliUser user, LiveMsg msg) {
        String url = "https://api.bilibili.com/x/v2/dm/post";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", appKey);
        params.put("access_key", user.getAccessToken());
        params.put("build", "105301");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        params.put("sign", sign(params, appSecret));
        params.put("type", "1");
        params.put("pool", String.valueOf(msg.getPool()));
        params.put("oid", msg.getCid().toString());
        params.put("bvid", msg.getBvid());
        params.put("msg", msg.getContext());
        params.put("color", String.valueOf(msg.getColor()));
        params.put("fontsize", String.valueOf(msg.getFontsize()));
        params.put("progress", msg.getSendTime().toString());
        params.put("mode", String.valueOf(msg.getMode()));
        params.put("rnd", String.valueOf(System.currentTimeMillis() * 1000000));
        Map<String, String> headers = getCommonHeaders();
        headers.put("cookie", user.getCookies());
        headers.put("x-bili-aurora-eid", "UlMFQVcABlAH");
        headers.put("x-bili-aurora-zone", "sh001");
        headers.put("app-key", "android64");
        String response = HttpClientUtil.post(url, headers, params, true);
        return JSON.parseObject(response, BiliDmResponse.class);
    }

    public static BiliReplyResponse sendVideoReply(BiliBiliUser user, BiliReply reply) {
        String url = "https://api.bilibili.com/x/v2/reply/add";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", appKey);
        params.put("access_key", user.getAccessToken());
        params.put("build", "105301");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        params.put("sign", sign(params, appSecret));
        params.put("type", reply.getType());
        params.put("message", reply.getMessage());
        params.put("oid", reply.getOid());
        if (StringUtils.isNotBlank(reply.getParent())) {
            params.put("root", reply.getRoot());
            params.put("parent", reply.getParent());
        }
        params.put("plat", "2");
        Map<String, String> headers = getCommonHeaders();
        headers.put("cookie", user.getCookies());
        headers.put("x-bili-aurora-eid", "UlMFQVcABlAH");
        headers.put("x-bili-aurora-zone", "sh001");
        headers.put("app-key", "android64");
        String response = HttpClientUtil.post(url, headers, params, true);
        return JSON.parseObject(response, BiliReplyResponse.class);
    }

    public static BiliReplyResponse topVideoReply(BiliBiliUser user, BiliReply reply) {
        String url = "https://api.bilibili.com/x/v2/reply/top";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", appKey);
        params.put("access_key", user.getAccessToken());
        params.put("build", "105301");
        params.put("channel", "html5_app_bili");
        params.put("mobi_app", "android");
        params.put("platform", "android");
        params.put("ts", "" + System.currentTimeMillis() / 1000);
        params.put("sign", sign(params, appSecret));
        params.put("type", reply.getType());
        params.put("oid", reply.getOid());
        params.put("rpid", reply.getRpid());
        params.put("action", reply.getAction());
        Map<String, String> headers = getCommonHeaders();
        headers.put("cookie", user.getCookies());
        headers.put("x-bili-aurora-eid", "UlMFQVcABlAH");
        headers.put("x-bili-aurora-zone", "sh001");
        headers.put("app-key", "android64");
        String response = HttpClientUtil.post(url, headers, params, true);
        return JSON.parseObject(response, BiliReplyResponse.class);
    }


    public static BiliResponseDto<GenerateQRDto> generateQRUrlTV() {
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", "4409e2ce8ffd12b8");
        params.put("local_id", "0");
        params.put("ts", "0");
        params.put("sign", "" + sign(params, "59b43e04ad6965f34319062b478f83dd"));
        String res = HttpClientUtil.post("https://passport.bilibili.com/x/passport-tv-login/qrcode/auth_code", new HashMap<>(), params, true);
        BiliResponseDto<GenerateQRDto> resp = JSON.parseObject(res, new TypeReference<BiliResponseDto<GenerateQRDto>>() {
        });
        return resp;
    }

    public static BiliResponseDto<GenerateQRDto> generateQRUrlWeb() {
        Map<String, String> headers = new TreeMap<>();
        String res = HttpClientUtil.get("https://passport.bilibili.com/x/passport-login/web/qrcode/generate", headers);
        BiliResponseDto<GenerateQRDto> resp = JSON.parseObject(res, new TypeReference<BiliResponseDto<GenerateQRDto>>() {
        });
        return resp;
    }


    public static String loginOnTV(String authCode) {
        String url = "https://passport.bilibili.com/x/passport-tv-login/qrcode/poll";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", "4409e2ce8ffd12b8");
        params.put("auth_code", authCode);
        params.put("local_id", "0");
        params.put("ts", "0");
        params.put("sign", "" + sign(params, "59b43e04ad6965f34319062b478f83dd"));
        return HttpClientUtil.post(url, new HashMap<>(), params, true);
    }

    public static BiliWebLoginDto loginOnWeb(String qrcodeKey) {
        String url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=" + qrcodeKey;
        Map<String, String> headers = new TreeMap<>();
        try {
            Response response = HttpClientUtil.getClient().newCall(new Request.Builder()
                    .url(url)
                    .headers(Headers.of(headers))
                    .get()
                    .build()
            ).execute();
            String loginResp = response.body().string();
            BiliWebLoginDto webLoginDto = JSON.parseObject(loginResp, BiliWebLoginDto.class);
            if (webLoginDto.getCode() == 0 && StringUtils.isNotBlank(webLoginDto.getData().getUrl())) {
                String url2 = webLoginDto.getData().getUrl();
                String SESSDATA = getParameterValueFromUrl(url2, "SESSDATA");
                String bili_jct = getParameterValueFromUrl(url2, "bili_jct");
                String DedeUserID = getParameterValueFromUrl(url2, "DedeUserID");
                String DedeUserID__ckMd5 = getParameterValueFromUrl(url2, "DedeUserID__ckMd5");
                String sid = getParameterValueFromUrl(url2, "sid");
                webLoginDto.setCookie("bili_jct=" + bili_jct + ";SESSDATA=" + SESSDATA + ";DedeUserID=" + DedeUserID + ";DedeUserID__ckMd5=" + DedeUserID__ckMd5 + ";sid+" + sid + ";");
            }
            return webLoginDto;
        } catch (
                UnknownHostException e) {
            try {
                Thread.sleep(5000L);
            } catch (Exception ignored) {
            }

        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Login.WebQr.PollFailed")
                    .add("qrcodeKey", qrcodeKey)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            throw new RuntimeException("loginOnWeb failed", e);
        }
        return null;
    }

    public static BiliResponseDto<BillBuvId> getBuvId() {
        String url = "https://api.bilibili.com/x/frontend/finger/spi";
        Map<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("referer", "https://live.bilibili.com/");
        additionalHeaders.put("user-agent", USER_AGENT);
        String res = HttpClientUtil.get(url, additionalHeaders);
        BiliResponseDto<BillBuvId> resp = JSON.parseObject(res, new TypeReference<BiliResponseDto<BillBuvId>>() {
        });
        return resp;
    }

    private static String getWbiMixinKey(WebCookie cookie) {
        long now = System.currentTimeMillis();
        WbiKeyCache cache = wbiKeyCache;
        if (cache != null && now < cache.expireAtMs) {
            return cache.mixinKey;
        }

        String url = "https://api.bilibili.com/x/web-interface/nav";
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", "https://member.bilibili.com");
        headers.put("Referer", "https://member.bilibili.com/");
        headers.put("cookie", buildWebCookieHeader(cookie));
        String resp = HttpClientUtil.get(url, headers);
        com.alibaba.fastjson.JSONObject root = com.alibaba.fastjson.JSONObject.parseObject(resp);
        com.alibaba.fastjson.JSONObject data = root == null ? null : root.getJSONObject("data");
        com.alibaba.fastjson.JSONObject wbiImg = data == null ? null : data.getJSONObject("wbi_img");
        String imgKey = extractWbiKey(wbiImg == null ? null : wbiImg.getString("img_url"));
        String subKey = extractWbiKey(wbiImg == null ? null : wbiImg.getString("sub_url"));
        if (StringUtils.isBlank(imgKey) || StringUtils.isBlank(subKey)) {
            throw new RuntimeException("getWbiMixinKey failed: " + resp);
        }
        String mixinKey = mixinWbiKey(imgKey, subKey);
        wbiKeyCache = new WbiKeyCache(mixinKey, now + 600_000L);
        return mixinKey;
    }

    private static void normalizeWebPublishBody(com.alibaba.fastjson.JSONObject jsonObject) {
        jsonObject.remove("build");
        jsonObject.remove("csrf");
        jsonObject.remove("open_elec");
        jsonObject.remove("topic_grey");
        jsonObject.remove("handle_staff");
        jsonObject.remove("is_360");
        jsonObject.remove("desc_format_id");
        removeBlank(jsonObject, "aid");
        removeBlank(jsonObject, "cover43");

        Object copyright = jsonObject.get("copyright");
        boolean isReprint = copyright instanceof Number && ((Number) copyright).intValue() == 2;
        if (!isReprint || StringUtils.isBlank(jsonObject.getString("source"))) {
            jsonObject.remove("source");
        }

        jsonObject.remove("desc_v2");
        jsonObject.remove("dynamic_v2");
    }

    private static void removeBlank(com.alibaba.fastjson.JSONObject jsonObject, String key) {
        if (StringUtils.isBlank(jsonObject.getString(key))) {
            jsonObject.remove(key);
        }
    }

    private static void removeNullOrEmpty(com.alibaba.fastjson.JSONObject jsonObject, String key) {
        Object value = jsonObject.get(key);
        if (value == null) {
            jsonObject.remove(key);
            return;
        }
        if (value instanceof Collection && ((Collection<?>) value).isEmpty()) {
            jsonObject.remove(key);
        }
    }

    private static String tokenHint(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        if (value.length() <= 12) {
            return "***#" + DigestUtils.sha1Hex(value).substring(0, 12);
        }
        return value.substring(0, 6) + "..." + value.substring(value.length() - 6) + "#" + DigestUtils.sha1Hex(value).substring(0, 12);
    }

    private static String signWbi(Map<String, String> params, String mixinKey) {
        String query = toQueryString(params);
        return DigestUtils.md5Hex(query + mixinKey);
    }

    private static String toQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .sorted(Map.Entry.comparingByKey())
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(sanitizeWbiValue(e.getValue())))
                .collect(Collectors.joining("&"));
    }

    private static String sanitizeWbiValue(String value) {
        return value == null ? "" : value.replaceAll("[!'()*]", "");
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, String.valueOf(StandardCharsets.UTF_8));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mixinWbiKey(String imgKey, String subKey) {
        String raw = imgKey + subKey;
        StringBuilder builder = new StringBuilder(64);
        for (int index : WBI_MIXIN_KEY_ENC_TAB) {
            if (index < raw.length()) {
                builder.append(raw.charAt(index));
            }
        }
        return builder.substring(0, Math.min(32, builder.length()));
    }

    private static String extractWbiKey(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        int slash = url.lastIndexOf('/');
        int dot = url.lastIndexOf('.');
        if (slash < 0 || dot <= slash) {
            return null;
        }
        return url.substring(slash + 1, dot);
    }

    private static String buildWebCookieHeader(WebCookie cookie) {
        String cookieHeader = cookie.getCookie();
        if (containsCookie(cookieHeader, "buvid3") && containsCookie(cookieHeader, "buvid4")) {
            return cookieHeader;
        }
        try {
            BiliResponseDto<BillBuvId> buvId = getBuvId();
            if (buvId != null && buvId.getData() != null) {
                StringBuilder builder = new StringBuilder(cookieHeader == null ? "" : cookieHeader);
                if (!containsCookie(builder.toString(), "buvid3") && StringUtils.isNotBlank(buvId.getData().getB3())) {
                    builder.append("buvid3=").append(buvId.getData().getB3()).append(";");
                }
                if (!containsCookie(builder.toString(), "buvid4") && StringUtils.isNotBlank(buvId.getData().getB4())) {
                    builder.append("buvid4=").append(buvId.getData().getB4()).append(";");
                }
                return builder.toString();
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("BiliApi.WebCookie.BuvidAppendFailed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
        return cookieHeader;
    }

    private static String normalizeWebCookieHeader(String cookie) {
        if (StringUtils.isBlank(cookie)) {
            return null;
        }
        try {
            return Cookie.parse(cookie).getCookie();
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("BiliApi.Cookie.NormalizeFailed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
            return cookie;
        }
    }

    private static boolean containsCookie(String cookieHeader, String key) {
        if (StringUtils.isBlank(cookieHeader) || StringUtils.isBlank(key)) {
            return false;
        }
        return Arrays.stream(cookieHeader.split(";"))
                .map(String::trim)
                .anyMatch(item -> item.startsWith(key + "="));
    }

    private static String buildWebEventTraceId(WebCookie cookie) {
        String buvid3 = extractCookieValue(cookie.getCookie(), "buvid3");
        if (StringUtils.isBlank(buvid3)) {
            buvid3 = BUVID;
        }
        return "Web:" + buvid3 + ":" + System.currentTimeMillis() + ":2";
    }

    private static String extractCookieValue(String cookieHeader, String key) {
        if (StringUtils.isBlank(cookieHeader) || StringUtils.isBlank(key)) {
            return null;
        }
        for (String item : cookieHeader.split(";")) {
            String pair = item.trim();
            if (pair.startsWith(key + "=")) {
                return pair.substring(key.length() + 1);
            }
        }
        return null;
    }

    private static class WbiKeyCache {
        private final String mixinKey;
        private final long expireAtMs;

        private WbiKeyCache(String mixinKey, long expireAtMs) {
            this.mixinKey = mixinKey;
            this.expireAtMs = expireAtMs;
        }
    }

    public static String refreshToken(BiliBiliUser user) {
        String url = "https://passport.bilibili.com/api/v2/oauth2/refresh_token";
        Map<String, String> params = new TreeMap<>();
        params.put("appkey", "4409e2ce8ffd12b8");
        params.put("access_token", user.getAccessToken());
        params.put("refresh_token", user.getRefreshToken());
        params.put("ts", String.valueOf(System.currentTimeMillis()));
        Map<String, String> headers = new HashMap<>();
        if (StringUtils.isNotBlank(user.getCookies())) {
            headers.put("cookie", user.getCookies());
        }
        params.put("sign", "" + sign(params, "59b43e04ad6965f34319062b478f83dd"));
        return HttpClientUtil.post(url, headers, params, true);
    }

    public static BiliUserCardResponseDto getUserCard(long uid) {
        String url = "https://account.bilibili.com/api/member/getCardByMid?mid=" + uid;
        Map<String, String> additionalHeaders = new HashMap<>();
        additionalHeaders.put("referer", "https://account.bilibili.com/api/member/getCardByMid?mid=" + uid);
        additionalHeaders.put("user-agent", USER_AGENT);
        String res = HttpClientUtil.get(url, additionalHeaders);
        try {
            return JSON.parseObject(res, new TypeReference<BiliUserCardResponseDto>() {
            });
        } catch (Exception e) {
            try {
                url = "https://workers.vrp.moe/api/bilibili/user-info/" + uid;
                res = HttpClientUtil.get(url, additionalHeaders);
                if (res.contains("card")) {
                    return JSON.parseObject(res, new TypeReference<BiliUserCardResponseDto>() {
                    });
                }
            } catch (Exception ignored) {
            }
            BiliUserCardResponseDto cardResponseDto = new BiliUserCardResponseDto();
            cardResponseDto.setCode(-400);
            return cardResponseDto;
        }
    }

    public static Map<Long, BiliUserCard> getUserCards(Collection<Long> uids) {
        return new LinkedHashMap<>();
    }

    public static Map<Long, BiliUserCard> getUserCards(Collection<Long> uids, String cookie) {
        Map<Long, BiliUserCard> result = new LinkedHashMap<>();
        if (uids == null || uids.isEmpty()) {
            return result;
        }
        String cookieHeader = normalizeWebCookieHeader(cookie);
        if (StringUtils.isBlank(cookieHeader) || !containsCookie(cookieHeader, "SESSDATA")) {
            return result;
        }

        List<Long> safeUids = uids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .limit(50)
                .toList();
        if (safeUids.isEmpty()) {
            return result;
        }

        String uidParam = safeUids.stream().map(String::valueOf).collect(Collectors.joining(","));
        String url = "https://api.bilibili.com/x/polymer/pc-electron/v1/user/cards?uids=" + uidParam;
        Map<String, String> headers = new HashMap<>();
        headers.put("referer", "https://www.bilibili.com/");
        headers.put("user-agent", USER_AGENT);
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("Cookie", cookieHeader);

        String res = HttpClientUtil.get(url, headers);
        com.alibaba.fastjson.JSONObject root = JSON.parseObject(res);
        if (root == null || root.getIntValue("code") != 0) {
            LogKvs kvs = LogKvs.event("BiliApi.UserCards.Failed")
                    .add("uids", uidParam)
                    .add("code", root == null ? null : root.getInteger("code"))
                    .addIfNotBlank("message", root == null ? null : root.getString("message"));
            if (root != null && root.getIntValue("code") == -101) {
                log.debug("[BLR] {}", kvs);
            } else {
                log.warn("[BLR] {}", kvs);
            }
            return result;
        }

        com.alibaba.fastjson.JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return result;
        }
        for (String key : data.keySet()) {
            com.alibaba.fastjson.JSONObject item = data.getJSONObject(key);
            if (item == null) {
                continue;
            }
            BiliUserCard card = item.toJavaObject(BiliUserCard.class);
            long mid = card.getMid();
            if (mid <= 0) {
                try {
                    mid = Long.parseLong(key);
                } catch (Exception ignored) {
                }
            }
            if (mid > 0) {
                result.put(mid, card);
            }
        }
        return result;
    }

    /**
     * 从URL中获取指定名称的查询参数值。
     *
     * @param url           URL字符串
     * @param parameterName 要获取的参数名称
     * @return 指定名称的参数值，如果不存在则返回null
     */
    public static String getParameterValueFromUrl(String url, String parameterName) {
        try {
            // 解析URL
            URI uri = new URI(url);
            String query = uri.getQuery();

            if (query == null || query.isEmpty()) {
                return null;
            }

            // 解析查询参数
            Map<String, String> parameters = new HashMap<>();
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2) {
                    parameters.put(pair[0], pair[1]);
                } else if (pair.length == 1) {
                    parameters.put(pair[0], "");
                }
            }

            // 返回指定名称的参数值
            return parameters.get(parameterName);

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        }
    }

    public static void main(String[] args) {
        log.info("[BLR] {}", LogKvs.event("BiliApi.QrUrl.Generated")
                .add("qrUrl", generateQRUrlTV()));
    }


    @Data
    @ToString
    public static class BiliUserCardResponseDto {
        // 0：成功 1：参数错误
        private long ts;
        private int code;
        private BiliUserCard card;
    }

    @Data
    public static class BiliResponseDto<T> {
        // 0：成功 1：参数错误
        private Integer code;
        private String msg;
        private String message;
        private T data;
    }

    @Data
    public static class GenerateQRDto {
        private String url;
        private String auth_code;
        private String qrcode_key;
    }
}
