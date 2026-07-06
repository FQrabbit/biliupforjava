package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.util.HttpClientUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

@Component
public class DingTalkWebhookNotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "dingtalk_webhook";

    private static final String WEBHOOK_SEND_URL = "https://oapi.dingtalk.com/robot/send?access_token=%s";

    private final DingTalkWebhookHttpClient httpClient;
    private final LongSupplier clockMillis;

    public DingTalkWebhookNotificationChannel() {
        this(new DefaultDingTalkWebhookHttpClient(), System::currentTimeMillis);
    }

    DingTalkWebhookNotificationChannel(DingTalkWebhookHttpClient httpClient, LongSupplier clockMillis) {
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
        String webhookUrl = resolveWebhookUrl(secret.getString("webhookUrl"));
        if (StringUtils.isBlank(webhookUrl)) {
            return NotificationSendResult.failed("DingTalk webhook url is empty");
        }

        try {
            String sendUrl = buildSignedWebhookUrl(webhookUrl, secret.getString("signSecret"), clockMillis.getAsLong());
            String resp = httpClient.postJson(sendUrl, buildPayload(config, message));
            return parseResponse(resp);
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    static String resolveWebhookUrl(String rawValue) {
        String value = StringUtils.defaultString(rawValue).trim();
        if (StringUtils.isBlank(value)) {
            return "";
        }
        if (StringUtils.startsWithIgnoreCase(value, "http://") || StringUtils.startsWithIgnoreCase(value, "https://")) {
            return value;
        }
        return WEBHOOK_SEND_URL.formatted(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    static String buildSignedWebhookUrl(String webhookUrl, String signSecret, long timestamp) {
        String url = StringUtils.defaultString(webhookUrl).trim();
        String secret = StringUtils.defaultString(signSecret).trim();
        if (StringUtils.isBlank(secret)) {
            return url;
        }
        String sign = sign(timestamp, secret);
        return appendQuery(url, "timestamp", String.valueOf(timestamp))
                + "&sign=" + URLEncoder.encode(sign, StandardCharsets.UTF_8);
    }

    static Map<String, Object> buildPayload(JSONObject config, NotificationMessage message) {
        String messageType = normalizeMessageType(config.getString("messageType"));
        String content = appendAtMarkers(
                applyKeyword(message == null ? "" : StringUtils.defaultString(message.getContent()), config.getString("keyword")),
                config
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", messageType);
        if ("text".equals(messageType)) {
            payload.put("text", Map.of("content", content));
        } else {
            Map<String, Object> markdown = new LinkedHashMap<>();
            markdown.put("title", buildTitle(message));
            markdown.put("text", content);
            payload.put("markdown", markdown);
        }

        Map<String, Object> at = buildAt(config);
        if (!at.isEmpty()) {
            payload.put("at", at);
        }
        return payload;
    }

    static NotificationSendResult parseResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return NotificationSendResult.failed("empty DingTalk webhook response");
        }
        try {
            JSONObject json = JSON.parseObject(response);
            int errcode = json.getIntValue("errcode");
            if (errcode == 0) {
                return NotificationSendResult.ok();
            }
            return NotificationSendResult.failed("DingTalk webhook errcode=" + errcode + ", " + StringUtils.defaultString(json.getString("errmsg")));
        } catch (Exception e) {
            return NotificationSendResult.failed("invalid DingTalk webhook response: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static Map<String, Object> buildAt(JSONObject config) {
        Map<String, Object> at = new LinkedHashMap<>();
        List<String> atMobiles = splitRecipients(config.getString("atMobiles"));
        List<String> atUserIds = splitRecipients(config.getString("atUserIds"));
        if (!atMobiles.isEmpty()) {
            at.put("atMobiles", atMobiles);
        }
        if (!atUserIds.isEmpty()) {
            at.put("atUserIds", atUserIds);
        }
        if (config.getBooleanValue("atAll")) {
            at.put("isAtAll", true);
        }
        return at;
    }

    private static List<String> splitRecipients(String rawValue) {
        return Arrays.stream(StringUtils.defaultString(rawValue).split("[|,，\\s]+"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private static String normalizeMessageType(String rawType) {
        String type = StringUtils.defaultString(rawType).trim();
        return "text".equals(type) ? "text" : "markdown";
    }

    private static String applyKeyword(String content, String rawKeyword) {
        String keyword = StringUtils.defaultString(rawKeyword).trim();
        if (StringUtils.isBlank(keyword) || StringUtils.contains(content, keyword)) {
            return content;
        }
        return StringUtils.defaultString(content) + "\n\n关键词: " + keyword;
    }

    private static String appendAtMarkers(String content, JSONObject config) {
        List<String> markers = Arrays.stream((StringUtils.defaultString(config.getString("atMobiles")) + "|" + StringUtils.defaultString(config.getString("atUserIds"))).split("[|,，\\s]+"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(value -> "@" + value)
                .filter(marker -> !StringUtils.contains(content, marker))
                .toList();
        if (markers.isEmpty()) {
            return content;
        }
        return StringUtils.defaultString(content) + "\n\n" + String.join(" ", markers);
    }

    private static String buildTitle(NotificationMessage message) {
        String title = message == null ? "" : StringUtils.defaultString(message.getTitle()).trim();
        if (StringUtils.isNotBlank(title)) {
            return title;
        }
        String content = message == null ? "" : StringUtils.defaultString(message.getContent()).trim();
        int idx = content.indexOf('\n');
        if (idx > 0) {
            return content.substring(0, idx).trim();
        }
        return StringUtils.defaultIfBlank(content, "biliupforjava");
    }

    private static String sign(long timestamp, String secret) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("DingTalk webhook sign failed: " + e.getMessage(), e);
        }
    }

    private static String appendQuery(String url, String key, String value) {
        String separator = url.contains("?") ? (url.endsWith("?") || url.endsWith("&") ? "" : "&") : "?";
        return url + separator + key + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    interface DingTalkWebhookHttpClient {
        String postJson(String url, Map<String, Object> payload);
    }

    private static class DefaultDingTalkWebhookHttpClient implements DingTalkWebhookHttpClient {
        @Override
        public String postJson(String url, Map<String, Object> payload) {
            return HttpClientUtil.postJson(url, new HashMap<>(), payload, false);
        }
    }
}
