package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.util.HttpClientUtil;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WeComWebhookNotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "wecom_webhook";

    private static final String WEBHOOK_SEND_URL = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=%s";
    private static final Pattern WEBHOOK_KEY_PATTERN = Pattern.compile("[?&]key=([^&#]+)", Pattern.CASE_INSENSITIVE);

    private final WeComWebhookHttpClient httpClient;

    public WeComWebhookNotificationChannel() {
        this(new DefaultWeComWebhookHttpClient());
    }

    WeComWebhookNotificationChannel(WeComWebhookHttpClient httpClient) {
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
        String webhookKey = resolveWebhookKey(secret.getString("webhookKey"));
        if (StringUtils.isBlank(webhookKey)) {
            return NotificationSendResult.failed("WeCom webhook key is empty");
        }

        try {
            String resp = httpClient.postJson(buildSendUrl(webhookKey), buildPayload(config, message));
            return parseResponse(resp);
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    static String resolveWebhookKey(String rawValue) {
        String value = StringUtils.defaultString(rawValue).trim();
        if (StringUtils.isBlank(value)) {
            return "";
        }
        Matcher matcher = WEBHOOK_KEY_PATTERN.matcher(value);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return value;
    }

    static String buildSendUrl(String webhookKey) {
        return WEBHOOK_SEND_URL.formatted(URLEncoder.encode(StringUtils.defaultString(webhookKey).trim(), StandardCharsets.UTF_8));
    }

    static Map<String, Object> buildPayload(JSONObject config, NotificationMessage message) {
        String messageType = normalizeMessageType(config.getString("messageType"));
        String content = message == null ? "" : StringUtils.defaultString(message.getContent());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", messageType);
        if ("markdown".equals(messageType)) {
            payload.put("markdown", Map.of("content", content));
            return payload;
        }

        Map<String, Object> text = new LinkedHashMap<>();
        text.put("content", content);
        List<String> mentionedList = splitRecipients(config.getString("mentionedList"));
        List<String> mentionedMobileList = splitRecipients(config.getString("mentionedMobileList"));
        if (!mentionedList.isEmpty()) {
            text.put("mentioned_list", mentionedList);
        }
        if (!mentionedMobileList.isEmpty()) {
            text.put("mentioned_mobile_list", mentionedMobileList);
        }
        payload.put("text", text);
        return payload;
    }

    static NotificationSendResult parseResponse(String response) {
        if (StringUtils.isBlank(response)) {
            return NotificationSendResult.failed("empty WeCom webhook response");
        }
        try {
            JSONObject json = JSON.parseObject(response);
            int errcode = json.getIntValue("errcode");
            if (errcode == 0) {
                return NotificationSendResult.ok();
            }
            return NotificationSendResult.failed("WeCom webhook errcode=" + errcode + ", " + StringUtils.defaultString(json.getString("errmsg")));
        } catch (Exception e) {
            return NotificationSendResult.failed("invalid WeCom webhook response: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static String normalizeMessageType(String rawType) {
        String type = StringUtils.defaultString(rawType).trim();
        return "markdown".equals(type) ? "markdown" : "text";
    }

    private static List<String> splitRecipients(String rawValue) {
        return Arrays.stream(StringUtils.defaultString(rawValue).split("[|,，\\s]+"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    interface WeComWebhookHttpClient {
        String postJson(String url, Map<String, Object> payload);
    }

    private static class DefaultWeComWebhookHttpClient implements WeComWebhookHttpClient {
        @Override
        public String postJson(String url, Map<String, Object> payload) {
            return HttpClientUtil.postJson(url, new HashMap<>(), payload, false);
        }
    }
}
