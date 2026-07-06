package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.NotificationChannel;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DingTalkWebhookNotificationChannelTest {

    @Test
    void resolveWebhookUrlAcceptsFullUrlOrRawAccessToken() {
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=abc123",
                DingTalkWebhookNotificationChannel.resolveWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=abc123"));
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=abc123",
                DingTalkWebhookNotificationChannel.resolveWebhookUrl("abc123"));
    }

    @Test
    void buildSignedWebhookUrlAppendsTimestampAndSign() {
        String url = DingTalkWebhookNotificationChannel.buildSignedWebhookUrl(
                "https://oapi.dingtalk.com/robot/send?access_token=abc123",
                "SEC000000",
                1700000000000L
        );

        assertTrue(url.startsWith("https://oapi.dingtalk.com/robot/send?access_token=abc123&timestamp=1700000000000&sign="));
        assertFalse(url.contains("SEC000000"));
        String encodedSign = url.substring(url.indexOf("&sign=") + 6);
        assertFalse(URLDecoder.decode(encodedSign, StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void buildTextPayloadIncludesAtAndKeyword() {
        JSONObject config = new JSONObject();
        config.put("messageType", "text");
        config.put("keyword", "biliup");
        config.put("atMobiles", "13800001111|13900002222");
        config.put("atUserIds", "user1 user2");
        config.put("atAll", true);
        NotificationMessage message = new NotificationMessage();
        message.setContent("hello");

        Map<String, Object> payload = DingTalkWebhookNotificationChannel.buildPayload(config, message);

        assertEquals("text", payload.get("msgtype"));
        assertEquals("hello\n\n关键词: biliup\n\n@13800001111 @13900002222 @user1 @user2", ((Map<?, ?>) payload.get("text")).get("content"));
        Map<?, ?> at = (Map<?, ?>) payload.get("at");
        assertEquals(List.of("13800001111", "13900002222"), at.get("atMobiles"));
        assertEquals(List.of("user1", "user2"), at.get("atUserIds"));
        assertEquals(true, at.get("isAtAll"));
    }

    @Test
    void buildMarkdownPayloadUsesMessageTitleAndContent() {
        JSONObject config = new JSONObject();
        config.put("messageType", "markdown");
        NotificationMessage message = new NotificationMessage();
        message.setTitle("开播提醒");
        message.setContent("### 主播开播了");

        Map<String, Object> payload = DingTalkWebhookNotificationChannel.buildPayload(config, message);

        assertEquals("markdown", payload.get("msgtype"));
        Map<?, ?> markdown = (Map<?, ?>) payload.get("markdown");
        assertEquals("开播提醒", markdown.get("title"));
        assertEquals("### 主播开播了", markdown.get("text"));
        assertFalse(payload.containsKey("at"));
    }

    @Test
    void parseResponseAcceptsSuccess() {
        NotificationSendResult result = DingTalkWebhookNotificationChannel.parseResponse("{\"errcode\":0,\"errmsg\":\"ok\"}");

        assertTrue(result.success());
    }

    @Test
    void parseResponseRejectsNonZeroErrcode() {
        NotificationSendResult result = DingTalkWebhookNotificationChannel.parseResponse("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}");

        assertFalse(result.success());
        assertEquals("DingTalk webhook errcode=310000, keywords not in content", result.errorMessage());
    }

    @Test
    void parseResponseRejectsEmptyOrInvalidJson() {
        assertFalse(DingTalkWebhookNotificationChannel.parseResponse("").success());
        assertFalse(DingTalkWebhookNotificationChannel.parseResponse("not-json").success());
    }

    @Test
    void sendRejectsMissingWebhookUrl() {
        DingTalkWebhookNotificationChannel channel = new DingTalkWebhookNotificationChannel(new FakeHttpClient(), () -> 1700000000000L);
        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{}");
        notificationChannel.setSecretJson("{}");

        NotificationSendResult result = channel.send(notificationChannel, new NotificationMessage());

        assertFalse(result.success());
        assertEquals("DingTalk webhook url is empty", result.errorMessage());
    }

    @Test
    void sendPostsPayloadToWebhookUrl() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.responses.add("{\"errcode\":0,\"errmsg\":\"ok\"}");
        DingTalkWebhookNotificationChannel channel = new DingTalkWebhookNotificationChannel(httpClient, () -> 1700000000000L);
        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{\"messageType\":\"markdown\",\"atAll\":true}");
        notificationChannel.setSecretJson("{\"webhookUrl\":\"abc123\",\"signSecret\":\"SEC000000\"}");
        NotificationMessage message = new NotificationMessage();
        message.setTitle("title");
        message.setContent("content");

        NotificationSendResult result = channel.send(notificationChannel, message);

        assertTrue(result.success());
        assertEquals(1, httpClient.urls.size());
        assertTrue(httpClient.urls.get(0).startsWith("https://oapi.dingtalk.com/robot/send?access_token=abc123&timestamp=1700000000000&sign="));
        assertEquals("markdown", httpClient.lastPayload.get("msgtype"));
        assertEquals("content", ((Map<?, ?>) httpClient.lastPayload.get("markdown")).get("text"));
        assertEquals(true, ((Map<?, ?>) httpClient.lastPayload.get("at")).get("isAtAll"));
    }

    private static class FakeHttpClient implements DingTalkWebhookNotificationChannel.DingTalkWebhookHttpClient {
        private final List<String> responses = new ArrayList<>();
        private final List<String> urls = new ArrayList<>();
        private Map<String, Object> lastPayload;

        @Override
        public String postJson(String url, Map<String, Object> payload) {
            urls.add(url);
            lastPayload = payload;
            return responses.get(urls.size() - 1);
        }
    }
}
