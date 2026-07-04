package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.util.HttpClientUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

@Component
public class WeComNotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "wecom_app";

    private static final String GET_TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=%s&corpsecret=%s";
    private static final String SEND_MESSAGE_URL = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=%s";
    private static final long TOKEN_REFRESH_AHEAD_MILLIS = 5 * 60 * 1000L;

    private final WeComHttpClient httpClient;
    private final LongSupplier clockMillis;
    private final Map<String, TokenCacheEntry> tokenCache = new ConcurrentHashMap<>();

    public WeComNotificationChannel() {
        this(new DefaultWeComHttpClient(), System::currentTimeMillis);
    }

    WeComNotificationChannel(WeComHttpClient httpClient, LongSupplier clockMillis) {
        this.httpClient = httpClient;
        this.clockMillis = clockMillis;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel, NotificationMessage message) {
        JSONObject config = NotificationJson.parse(channel.getConfigJson());
        JSONObject secret = NotificationJson.parse(channel.getSecretJson());
        String corpId = StringUtils.trimToEmpty(config.getString("corpId"));
        String corpSecret = StringUtils.trimToEmpty(secret.getString("corpSecret"));
        String agentId = StringUtils.trimToEmpty(config.getString("agentId"));

        NotificationSendResult validation = validateConfig(corpId, corpSecret, agentId);
        if (!validation.success()) {
            return validation;
        }

        try {
            TokenResult tokenResult = getAccessToken(corpId, corpSecret);
            if (!tokenResult.success()) {
                return NotificationSendResult.failed(tokenResult.errorMessage());
            }
            Map<String, Object> payload = buildPayload(config, message);
            String resp = httpClient.postJson(SEND_MESSAGE_URL.formatted(urlEncode(tokenResult.accessToken())), payload);
            return parseSendResponse(resp);
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    TokenResult getAccessToken(String corpId, String corpSecret) {
        String cacheKey = corpId + '\n' + corpSecret;
        long now = clockMillis.getAsLong();
        TokenCacheEntry cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAtMillis() > now) {
            return TokenResult.ok(cached.accessToken());
        }

        String url = GET_TOKEN_URL.formatted(urlEncode(corpId), urlEncode(corpSecret));
        String resp = httpClient.get(url);
        TokenResult tokenResult = parseTokenResponse(resp, now);
        if (tokenResult.success()) {
            tokenCache.put(cacheKey, new TokenCacheEntry(tokenResult.accessToken(), tokenResult.expiresAtMillis()));
        }
        return tokenResult;
    }

    static NotificationSendResult validateConfig(String corpId, String corpSecret, String agentId) {
        if (StringUtils.isBlank(corpId)) {
            return NotificationSendResult.failed("WeCom corpId is empty");
        }
        if (StringUtils.isBlank(corpSecret)) {
            return NotificationSendResult.failed("WeCom corpSecret is empty");
        }
        if (StringUtils.isBlank(agentId)) {
            return NotificationSendResult.failed("WeCom agentId is empty");
        }
        if (!StringUtils.isNumeric(agentId)) {
            return NotificationSendResult.failed("WeCom agentId must be numeric");
        }
        return NotificationSendResult.ok();
    }

    static Map<String, Object> buildPayload(JSONObject config, NotificationMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String toUser = StringUtils.trimToEmpty(config.getString("toUser"));
        String toParty = StringUtils.trimToEmpty(config.getString("toParty"));
        String toTag = StringUtils.trimToEmpty(config.getString("toTag"));
        if (StringUtils.isBlank(toUser) && StringUtils.isBlank(toParty) && StringUtils.isBlank(toTag)) {
            toUser = "@all";
        }
        putIfNotBlank(payload, "touser", toUser);
        putIfNotBlank(payload, "toparty", toParty);
        putIfNotBlank(payload, "totag", toTag);
        payload.put("msgtype", "text");
        payload.put("agentid", Long.parseLong(StringUtils.trimToEmpty(config.getString("agentId"))));
        payload.put("text", Map.of("content", buildContent(message)));
        payload.put("safe", config.getBooleanValue("safe") ? 1 : 0);
        return payload;
    }

    static TokenResult parseTokenResponse(String response, long nowMillis) {
        if (StringUtils.isBlank(response)) {
            return TokenResult.failed("empty WeCom token response");
        }
        try {
            JSONObject json = JSON.parseObject(response);
            int errcode = json.getIntValue("errcode");
            if (errcode != 0) {
                return TokenResult.failed("WeCom gettoken errcode=" + errcode + ", " + StringUtils.defaultString(json.getString("errmsg")));
            }
            String accessToken = json.getString("access_token");
            if (StringUtils.isBlank(accessToken)) {
                return TokenResult.failed("WeCom access_token is empty");
            }
            long expiresInMillis = Math.max(0L, json.getLongValue("expires_in") * 1000L - TOKEN_REFRESH_AHEAD_MILLIS);
            return TokenResult.ok(accessToken, nowMillis + expiresInMillis);
        } catch (Exception e) {
            return TokenResult.failed("invalid WeCom token response: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    static NotificationSendResult parseSendResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return NotificationSendResult.failed("empty WeCom send response");
        }
        try {
            JSONObject json = JSON.parseObject(response);
            int errcode = json.getIntValue("errcode");
            if (errcode == 0 && !hasInvalidRecipients(json)) {
                return NotificationSendResult.ok();
            }
            return NotificationSendResult.failed(buildSendError(json, errcode));
        } catch (Exception e) {
            return NotificationSendResult.failed("invalid WeCom send response: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static boolean hasInvalidRecipients(JSONObject json) {
        return StringUtils.isNotBlank(json.getString("invaliduser"))
                || StringUtils.isNotBlank(json.getString("invalidparty"))
                || StringUtils.isNotBlank(json.getString("invalidtag"))
                || StringUtils.isNotBlank(json.getString("unlicenseduser"));
    }

    private static String buildSendError(JSONObject json, int errcode) {
        StringBuilder sb = new StringBuilder("WeCom send errcode=")
                .append(errcode)
                .append(", ")
                .append(StringUtils.defaultString(json.getString("errmsg")));
        appendIfNotBlank(sb, "invaliduser", json.getString("invaliduser"));
        appendIfNotBlank(sb, "invalidparty", json.getString("invalidparty"));
        appendIfNotBlank(sb, "invalidtag", json.getString("invalidtag"));
        appendIfNotBlank(sb, "unlicenseduser", json.getString("unlicenseduser"));
        return sb.toString();
    }

    private static void appendIfNotBlank(StringBuilder sb, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append(", ").append(key).append('=').append(value);
        }
    }

    private static String buildContent(NotificationMessage message) {
        if (message == null) {
            return "";
        }
        return StringUtils.defaultString(message.getContent());
    }

    private static void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            payload.put(key, value.trim());
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }

    interface WeComHttpClient {
        String get(String url);

        String postJson(String url, Map<String, Object> payload);
    }

    private static class DefaultWeComHttpClient implements WeComHttpClient {
        @Override
        public String get(String url) {
            return HttpClientUtil.get(url, new HashMap<>());
        }

        @Override
        public String postJson(String url, Map<String, Object> payload) {
            return HttpClientUtil.postJson(url, new HashMap<>(), payload, false);
        }
    }

    record TokenResult(boolean success, String accessToken, long expiresAtMillis, String errorMessage) {
        static TokenResult ok(String accessToken) {
            return ok(accessToken, Long.MAX_VALUE);
        }

        static TokenResult ok(String accessToken, long expiresAtMillis) {
            return new TokenResult(true, accessToken, expiresAtMillis, null);
        }

        static TokenResult failed(String errorMessage) {
            return new TokenResult(false, null, 0L, errorMessage);
        }
    }

    private record TokenCacheEntry(String accessToken, long expiresAtMillis) {
    }
}
