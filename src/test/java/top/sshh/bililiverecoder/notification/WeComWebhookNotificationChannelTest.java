package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.NotificationChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComWebhookNotificationChannelTest {

    @Test
    void resolveWebhookKeyAcceptsFullUrlOrRawKey() {
        assertEquals("abc123", WeComWebhookNotificationChannel.resolveWebhookKey("abc123"));
        assertEquals("abc123", WeComWebhookNotificationChannel.resolveWebhookKey("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123"));
        assertEquals("abc123", WeComWebhookNotificationChannel.resolveWebhookKey("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?debug=1&key=abc123&x=2"));
    }

    @Test
    void buildTextPayloadIncludesMentions() {
        JSONObject config = new JSONObject();
        config.put("messageType", "text");
        config.put("mentionedList", "user1|user2|@all");
        config.put("mentionedMobileList", "13800001111|13900002222");
        NotificationMessage message = new NotificationMessage();
        message.setContent("hello");

        Map<String, Object> payload = WeComWebhookNotificationChannel.buildPayload(config, message);

        assertEquals("text", payload.get("msgtype"));
        Map<?, ?> text = (Map<?, ?>) payload.get("text");
        assertEquals("hello", text.get("content"));
        assertEquals(List.of("user1", "user2", "@all"), text.get("mentioned_list"));
        assertEquals(List.of("13800001111", "13900002222"), text.get("mentioned_mobile_list"));
    }

    @Test
    void buildMarkdownPayloadUsesMarkdownContentOnly() {
        JSONObject config = new JSONObject();
        config.put("messageType", "markdown");
        config.put("mentionedList", "@all");
        NotificationMessage message = new NotificationMessage();
        message.setContent("**hello**");

        Map<String, Object> payload = WeComWebhookNotificationChannel.buildPayload(config, message);

        assertEquals("markdown", payload.get("msgtype"));
        assertEquals(Map.of("content", "**hello**"), payload.get("markdown"));
        assertFalse(payload.containsKey("text"));
    }

    @Test
    void parseResponseAcceptsSuccess() {
        NotificationSendResult result = WeComWebhookNotificationChannel.parseResponse("{\"errcode\":0,\"errmsg\":\"ok\"}");

        assertTrue(result.success());
    }

    @Test
    void parseResponseRejectsNonZeroErrcode() {
        NotificationSendResult result = WeComWebhookNotificationChannel.parseResponse("{\"errcode\":93000,\"errmsg\":\"invalid webhook\"}");

        assertFalse(result.success());
        assertEquals("WeCom webhook errcode=93000, invalid webhook", result.errorMessage());
    }

    @Test
    void parseResponseRejectsEmptyOrInvalidJson() {
        assertFalse(WeComWebhookNotificationChannel.parseResponse("").success());
        assertFalse(WeComWebhookNotificationChannel.parseResponse("not-json").success());
    }

    @Test
    void sendRejectsMissingWebhookKey() {
        WeComWebhookNotificationChannel channel = new WeComWebhookNotificationChannel(new FakeHttpClient());
        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{}");
        notificationChannel.setSecretJson("{}");

        NotificationSendResult result = channel.send(notificationChannel, new NotificationMessage());

        assertFalse(result.success());
        assertEquals("WeCom webhook key is empty", result.errorMessage());
    }

    @Test
    void sendPostsPayloadToWebhookUrl() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.responses.add("{\"errcode\":0,\"errmsg\":\"ok\"}");
        WeComWebhookNotificationChannel channel = new WeComWebhookNotificationChannel(httpClient);
        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{\"messageType\":\"text\",\"mentionedList\":\"@all\"}");
        notificationChannel.setSecretJson("{\"webhookKey\":\"https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123\"}");
        NotificationMessage message = new NotificationMessage();
        message.setContent("content");

        NotificationSendResult result = channel.send(notificationChannel, message);

        assertTrue(result.success());
        assertEquals(1, httpClient.urls.size());
        assertEquals("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc123", httpClient.urls.get(0));
        assertEquals("text", httpClient.lastPayload.get("msgtype"));
        assertEquals("content", ((Map<?, ?>) httpClient.lastPayload.get("text")).get("content"));
    }

    private static class FakeHttpClient implements WeComWebhookNotificationChannel.WeComWebhookHttpClient {
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
