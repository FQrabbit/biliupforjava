package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.util.HttpClientUtil;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class BarkNotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "bark";

    static final String DEFAULT_SERVER_URL = "https://api.day.app";
    private static final String DEFAULT_GROUP = "biliupforjava";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel, NotificationMessage notificationMessage) {
        JSONObject config = NotificationJson.parse(channel.getConfigJson());
        JSONObject secret = NotificationJson.parse(channel.getSecretJson());
        BarkEndpoint endpoint = resolveEndpoint(config.getString("serverUrl"), secret.getString("deviceKey"));
        if (StringUtils.isBlank(endpoint.deviceKey())) {
            return NotificationSendResult.failed("Bark device key is empty");
        }

        Map<String, Object> payload = buildPayload(endpoint.deviceKey(), config, notificationMessage);
        try {
            String resp = HttpClientUtil.postJson(
                    endpoint.pushUrl(),
                    new HashMap<>(),
                    payload,
                    false
            );
            return parseResponse(resp);
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    static BarkEndpoint resolveEndpoint(String configuredServerUrl, String configuredDeviceKey) {
        String serverUrl = normalizeServerUrl(configuredServerUrl);
        String deviceKey = StringUtils.defaultString(configuredDeviceKey).trim();

        if (StringUtils.startsWithIgnoreCase(deviceKey, "http://") || StringUtils.startsWithIgnoreCase(deviceKey, "https://")) {
            BarkEndpoint parsed = parseTestUrl(deviceKey);
            if (StringUtils.isNotBlank(parsed.deviceKey())) {
                return parsed;
            }
        }

        if (StringUtils.startsWithIgnoreCase(serverUrl, "http://") || StringUtils.startsWithIgnoreCase(serverUrl, "https://")) {
            BarkEndpoint parsed = parseTestUrl(serverUrl);
            if (StringUtils.isNotBlank(parsed.deviceKey())) {
                return parsed;
            }
        }

        return new BarkEndpoint(serverUrl + "/push", deviceKey);
    }

    static Map<String, Object> buildPayload(String deviceKey, JSONObject config, NotificationMessage message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("device_key", StringUtils.defaultString(deviceKey).trim());
        payload.put("title", buildTitle(message));
        payload.put("body", buildBody(message));

        putIfNotBlank(payload, "group", StringUtils.defaultIfBlank(config.getString("group"), DEFAULT_GROUP));
        putIfNotBlank(payload, "sound", config.getString("sound"));
        putIfNotBlank(payload, "icon", config.getString("icon"));
        putIfNotBlank(payload, "level", normalizeLevel(config.getString("level")));
        return payload;
    }

    static NotificationSendResult parseResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return NotificationSendResult.failed("empty Bark response");
        }
        try {
            JSONObject json = JSON.parseObject(response);
            Integer code = json.getInteger("code");
            if (code != null && code == 200) {
                return NotificationSendResult.ok();
            }
            String message = StringUtils.defaultIfBlank(json.getString("message"), json.getString("error"));
            return NotificationSendResult.failed("Bark code=" + (code == null ? -1 : code) + ", " + StringUtils.defaultString(message));
        } catch (Exception e) {
            return NotificationSendResult.failed("invalid Bark response: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static BarkEndpoint parseTestUrl(String rawUrl) {
        String url = StringUtils.defaultString(rawUrl).trim();
        int schemeIdx = url.indexOf("://");
        if (schemeIdx < 0) {
            return new BarkEndpoint(normalizeServerUrl(null) + "/push", "");
        }
        int hostStart = schemeIdx + 3;
        int pathStart = url.indexOf('/', hostStart);
        if (pathStart < 0 || pathStart >= url.length() - 1) {
            return new BarkEndpoint(normalizeServerUrl(url) + "/push", "");
        }
        String base = url.substring(0, pathStart);
        String path = url.substring(pathStart + 1);
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        String firstSegment = path.split("/", 2)[0].trim();
        if ("push".equalsIgnoreCase(firstSegment)) {
            return new BarkEndpoint(normalizeServerUrl(base) + "/push", "");
        }
        return new BarkEndpoint(normalizeServerUrl(base) + "/push", firstSegment);
    }

    private static String normalizeServerUrl(String rawServerUrl) {
        String serverUrl = StringUtils.defaultIfBlank(rawServerUrl, DEFAULT_SERVER_URL).trim();
        while (serverUrl.endsWith("/")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
        }
        if (serverUrl.endsWith("/push")) {
            serverUrl = serverUrl.substring(0, serverUrl.length() - "/push".length());
        }
        if (!StringUtils.startsWithIgnoreCase(serverUrl, "http://") && !StringUtils.startsWithIgnoreCase(serverUrl, "https://")) {
            serverUrl = "https://" + serverUrl;
        }
        return StringUtils.defaultIfBlank(serverUrl, DEFAULT_SERVER_URL);
    }

    private static String buildTitle(NotificationMessage message) {
        String title = message == null ? null : message.getTitle();
        if (StringUtils.isBlank(title) && message != null) {
            title = message.getContent();
        }
        title = StringUtils.defaultIfBlank(firstLine(title), "biliupforjava");
        return title.length() > 64 ? title.substring(0, 64) : title;
    }

    private static String buildBody(NotificationMessage message) {
        if (message == null) {
            return "";
        }
        return StringUtils.defaultString(message.getContent());
    }

    private static String firstLine(String text) {
        String safe = StringUtils.defaultString(text).trim();
        int idx = safe.indexOf('\n');
        return idx > 0 ? safe.substring(0, idx).trim() : safe;
    }

    private static String normalizeLevel(String rawLevel) {
        String level = StringUtils.defaultString(rawLevel).trim();
        return switch (level) {
            case "critical", "active", "timeSensitive", "passive" -> level;
            default -> "active";
        };
    }

    private static void putIfNotBlank(Map<String, Object> payload, String key, String value) {
        if (StringUtils.isNotBlank(value)) {
            payload.put(key, value.trim());
        }
    }

    record BarkEndpoint(String pushUrl, String deviceKey) {
    }
}
