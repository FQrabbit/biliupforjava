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

class NtfyNotificationChannelTest {

    @Test
    void buildPublishUrlUsesServerRootForJsonPublish() {
        assertEquals("https://ntfy.sh", NtfyNotificationChannel.buildPublishUrl(""));
        assertEquals("https://ntfy.example.com", NtfyNotificationChannel.buildPublishUrl("ntfy.example.com/"));
        assertEquals("http://127.0.0.1:8080", NtfyNotificationChannel.buildPublishUrl("http://127.0.0.1:8080/"));
    }

    @Test
    void buildHeadersSupportsBearerAndBasicAuth() {
        JSONObject config = new JSONObject();
        JSONObject secret = new JSONObject();

        config.put("authType", "bearer");
        secret.put("token", "tk_123");
        assertEquals("Bearer tk_123", NtfyNotificationChannel.buildHeaders(config, secret).get("Authorization"));

        config.put("authType", "basic");
        secret.clear();
        secret.put("username", "user");
        secret.put("password", "pass");
        assertEquals("Basic dXNlcjpwYXNz", NtfyNotificationChannel.buildHeaders(config, secret).get("Authorization"));

        config.put("authType", "none");
        assertTrue(NtfyNotificationChannel.buildHeaders(config, secret).isEmpty());
    }

    @Test
    void buildPayloadIncludesRequiredAndOptionalFields() {
        JSONObject config = new JSONObject();
        config.put("topic", "/room-started/");
        config.put("title", "旧版本地标题");
        config.put("priority", "urgent");
        config.put("tags", "tv,warning  bilibili");
        config.put("click", "https://live.bilibili.com/123");
        config.put("markdown", true);
        NotificationMessage message = new NotificationMessage();
        message.setTitle("开播提醒");
        message.setContent("主播【测试】开播了");

        Map<String, Object> payload = NtfyNotificationChannel.buildPayload(config, message);

        assertEquals("room-started", payload.get("topic"));
        assertEquals("主播【测试】开播了", payload.get("message"));
        assertEquals("开播提醒", payload.get("title"));
        assertEquals(5, payload.get("priority"));
        assertEquals(List.of("tv", "warning", "bilibili"), payload.get("tags"));
        assertEquals("https://live.bilibili.com/123", payload.get("click"));
        assertEquals(true, payload.get("markdown"));
    }

    @Test
    void buildPayloadFallsBackToMessageTitleAndDefaultPriority() {
        JSONObject config = new JSONObject();
        config.put("topic", "topic");
        NotificationMessage message = new NotificationMessage();
        message.setTitle("测试通知");
        message.setContent("正文");

        Map<String, Object> payload = NtfyNotificationChannel.buildPayload(config, message);

        assertEquals("测试通知", payload.get("title"));
        assertEquals(3, payload.get("priority"));
        assertFalse(payload.containsKey("tags"));
        assertFalse(payload.containsKey("markdown"));
    }

    @Test
    void parseResponseAcceptsNtfyMessageId() {
        NotificationSendResult result = NtfyNotificationChannel.parseResponse("{\"id\":\"abc\",\"time\":1710000000,\"event\":\"message\",\"topic\":\"topic\"}");

        assertTrue(result.success());
    }

    @Test
    void parseResponseRejectsErrorResponse() {
        NotificationSendResult result = NtfyNotificationChannel.parseResponse("{\"code\":40001,\"error\":\"invalid request\"}");

        assertFalse(result.success());
        assertEquals("ntfy code=40001, invalid request", result.errorMessage());
    }

    @Test
    void sendRejectsMissingTopic() {
        NtfyNotificationChannel channel = new NtfyNotificationChannel(new FakeHttpClient());
        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{}");
        notificationChannel.setSecretJson("{}");

        NotificationSendResult result = channel.send(notificationChannel, new NotificationMessage());

        assertFalse(result.success());
        assertEquals("ntfy topic is empty", result.errorMessage());
    }

    @Test
    void sendPostsJsonPayloadToConfiguredServer() {
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.responses.add("{\"id\":\"abc\",\"topic\":\"live\"}");
        NtfyNotificationChannel channel = new NtfyNotificationChannel(httpClient);
        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{\"serverUrl\":\"ntfy.example.com\",\"topic\":\"live\",\"authType\":\"bearer\",\"priority\":\"high\"}");
        notificationChannel.setSecretJson("{\"token\":\"secret-token\"}");
        NotificationMessage message = new NotificationMessage();
        message.setTitle("title");
        message.setContent("content");

        NotificationSendResult result = channel.send(notificationChannel, message);

        assertTrue(result.success());
        assertEquals("https://ntfy.example.com", httpClient.urls.get(0));
        assertEquals("Bearer secret-token", httpClient.lastHeaders.get("Authorization"));
        assertEquals("live", httpClient.lastPayload.get("topic"));
        assertEquals("content", httpClient.lastPayload.get("message"));
        assertEquals(4, httpClient.lastPayload.get("priority"));
    }

    private static class FakeHttpClient implements NtfyNotificationChannel.NtfyHttpClient {
        private final List<String> responses = new ArrayList<>();
        private final List<String> urls = new ArrayList<>();
        private Map<String, String> lastHeaders;
        private Map<String, Object> lastPayload;

        @Override
        public String postJson(String url, Map<String, String> headers, Map<String, Object> payload) {
            urls.add(url);
            lastHeaders = headers;
            lastPayload = payload;
            return responses.get(urls.size() - 1);
        }
    }
}
