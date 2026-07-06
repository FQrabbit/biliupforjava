package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.util.HttpClientUtil;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NtfyNotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "ntfy";

    static final String DEFAULT_SERVER_URL = "https://ntfy.sh";

    private final NtfyHttpClient httpClient;

    public NtfyNotificationChannel() {
        this(new DefaultNtfyHttpClient());
    }

    NtfyNotificationChannel(NtfyHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel, NotificationMessage message) {
        JSONObject config = NotificationJson.parse(channel.getConfigJson());
        JSONObject secret = NotificationJson.parse(channel.getSecretJson());
        String topic = config.getString("topic");
        if (StringUtils.isBlank(topic)) {
            return NotificationSendResult.failed("ntfy topic is empty");
        }

        try {
            String resp = httpClient.postJson(
                    buildPublishUrl(config.getString("serverUrl")),
                    buildHeaders(config, secret),
                    buildPayload(config, message)
            );
            return parseResponse(resp);
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    static String buildPublishUrl(String rawServerUrl) {
        return normalizeServerUrl(rawServerUrl);
    }

    static Map<String, String> buildHeaders(JSONObject config, JSONObject secret) {
        Map<String, String> headers = new LinkedHashMap<>();
        String authType = normalizeAuthType(config.getString("authType"));
        if ("bearer".equals(authType)) {
            putHeaderIfNotBlank(headers, "Authorization", bearerAuth(secret.getString("token")));
        } else if ("basic".equals(authType)) {
            putHeaderIfNotBlank(headers, "Authorization", basicAuth(secret.getString("username"), secret.getString("password")));
        }
        return headers;
    }

    static Map<String, Object> buildPayload(JSONObject config, NotificationMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("topic", normalizeTopic(config.getString("topic")));
        payload.put("message", message == null ? "" : StringUtils.defaultString(message.getContent()));
        putIfNotBlank(payload, "title", buildTitle(message));
        List<String> tags = splitTags(config.getString("tags"));
        if (!tags.isEmpty()) {
            payload.put("tags", tags);
        }
        putIfNotBlank(payload, "click", config.getString("click"));
        payload.put("priority", normalizePriority(config.getString("priority")));
        if (config.getBooleanValue("markdown")) {
            payload.put("markdown", true);
        }
        return payload;
    }

    static NotificationSendResult parseResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return NotificationSendResult.failed("empty ntfy response");
        }
        try {
            JSONObject json = JSON.parseObject(response);
            if (StringUtils.isNotBlank(json.getString("error"))) {
                Integer code = json.getInteger("code");
                return NotificationSendResult.failed("ntfy code=" + (code == null ? -1 : code) + ", " + json.getString("error"));
            }
            if (StringUtils.isNotBlank(json.getString("id"))) {
                return NotificationSendResult.ok();
            }
            return NotificationSendResult.failed("invalid ntfy response: missing id");
        } catch (Exception e) {
            return NotificationSendResult.failed("invalid ntfy response: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static String normalizeServerUrl(String rawServerUrl) {
        String serverUrl = StringUtils.defaultIfBlank(rawServerUrl, DEFAULT_SERVER_URL).trim();
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        if (!StringUtils.startsWithIgnoreCase(serverUrl, "http://") && !StringUtils.startsWithIgnoreCase(serverUrl, "https://")) {
            serverUrl = "https://" + serverUrl;
        }
        return StringUtils.defaultIfBlank(serverUrl, DEFAULT_SERVER_URL);
    }

    private static List<String> splitTags(String rawTags) {
        return Arrays.stream(StringUtils.defaultString(rawTags).split("[,，|\\s]+"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private static String normalizeTopic(String rawTopic) {
        String topic = StringUtils.defaultString(rawTopic).trim();
        while (topic.startsWith("/")) {
            topic = topic.substring(1);
        }
        while (topic.endsWith("/")) {
            topic = topic.substring(0, topic.length() - 1);
        }
        return topic;
    }

    private static String buildTitle(NotificationMessage message) {
        String title = message == null ? "" : message.getTitle();
        if (StringUtils.isBlank(title) && message != null) {
            title = firstLine(message.getContent());
        }
        return StringUtils.defaultIfBlank(title, "biliupforjava");
    }

    private static String firstLine(String text) {
        String safe = StringUtils.defaultString(text).trim();
        int idx = safe.indexOf('\n');
        return idx > 0 ? safe.substring(0, idx).trim() : safe;
    }

    private static int normalizePriority(String rawPriority) {
        String priority = StringUtils.defaultString(rawPriority).trim().toLowerCase();
        return switch (priority) {
            case "min", "1" -> 1;
            case "low", "2" -> 2;
            case "high", "4" -> 4;
            case "urgent", "max", "5" -> 5;
            default -> 3;
        };
    }

    private static String normalizeAuthType(String rawAuthType) {
        String authType = StringUtils.defaultString(rawAuthType).trim().toLowerCase();
        return switch (authType) {
            case "bearer", "basic" -> authType;
            default -> "none";
        };
    }

    private static String bearerAuth(String token) {
        String value = StringUtils.defaultString(token).trim();
        return StringUtils.isBlank(value) ? "" : "Bearer " + value;
    }

    private static String basicAuth(String username, String password) {
        if (StringUtils.isBlank(username) && StringUtils.isBlank(password)) {
            return "";
        }
        String source = StringUtils.defaultString(username).trim() + ":" + StringUtils.defaultString(password);
        return "Basic " + Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value.trim());
        }
    }

    private static void putHeaderIfNotBlank(Map<String, String> target, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            target.put(key, value.trim());
        }
    }

    interface NtfyHttpClient {
        String postJson(String url, Map<String, String> headers, Map<String, Object> payload);
    }

    private static class DefaultNtfyHttpClient implements NtfyHttpClient {
        @Override
        public String postJson(String url, Map<String, String> headers, Map<String, Object> payload) {
            return HttpClientUtil.postJson(url, new HashMap<>(headers), payload, false);
        }
    }
}
