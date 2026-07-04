package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.NotificationChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComNotificationChannelTest {

    @Test
    void validateConfigRejectsMissingRequiredFields() {
        assertFalse(WeComNotificationChannel.validateConfig("", "secret", "1001").success());
        assertFalse(WeComNotificationChannel.validateConfig("corp", "", "1001").success());
        assertFalse(WeComNotificationChannel.validateConfig("corp", "secret", "").success());
        assertFalse(WeComNotificationChannel.validateConfig("corp", "secret", "abc").success());
    }

    @Test
    void parseTokenResponseRejectsNonZeroErrcode() {
        WeComNotificationChannel.TokenResult result = WeComNotificationChannel.parseTokenResponse(
                "{\"errcode\":40013,\"errmsg\":\"invalid corpid\"}",
                1000L
        );

        assertFalse(result.success());
        assertEquals("WeCom gettoken errcode=40013, invalid corpid", result.errorMessage());
    }

    @Test
    void tokenCacheReusesUnexpiredTokenAndRefreshesExpiredToken() {
        AtomicLong now = new AtomicLong(1000L);
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.getResponses.add("{\"errcode\":0,\"access_token\":\"token-a\",\"expires_in\":301}");
        httpClient.getResponses.add("{\"errcode\":0,\"access_token\":\"token-b\",\"expires_in\":301}");
        WeComNotificationChannel channel = new WeComNotificationChannel(httpClient, now::get);

        assertEquals("token-a", channel.getAccessToken("corp", "secret").accessToken());
        assertEquals("token-a", channel.getAccessToken("corp", "secret").accessToken());
        assertEquals(1, httpClient.getCalls);

        now.set(3000L);

        assertEquals("token-b", channel.getAccessToken("corp", "secret").accessToken());
        assertEquals(2, httpClient.getCalls);
    }

    @Test
    void buildPayloadIncludesTextMessageFields() {
        JSONObject config = new JSONObject();
        config.put("agentId", "100001");
        config.put("toUser", "user1|user2");
        config.put("toParty", "2");
        config.put("toTag", "3");
        config.put("safe", true);
        NotificationMessage message = new NotificationMessage();
        message.setContent("hello");

        Map<String, Object> payload = WeComNotificationChannel.buildPayload(config, message);

        assertEquals("user1|user2", payload.get("touser"));
        assertEquals("2", payload.get("toparty"));
        assertEquals("3", payload.get("totag"));
        assertEquals(100001L, payload.get("agentid"));
        assertEquals("text", payload.get("msgtype"));
        assertEquals(Map.of("content", "hello"), payload.get("text"));
        assertEquals(1, payload.get("safe"));
    }

    @Test
    void buildPayloadDefaultsToAllOnlyWhenRecipientScopeIsEmpty() {
        JSONObject config = new JSONObject();
        config.put("agentId", "100001");

        Map<String, Object> defaultPayload = WeComNotificationChannel.buildPayload(config, new NotificationMessage());
        assertEquals("@all", defaultPayload.get("touser"));

        config.put("toParty", "2");
        Map<String, Object> partyPayload = WeComNotificationChannel.buildPayload(config, new NotificationMessage());
        assertFalse(partyPayload.containsKey("touser"));
        assertEquals("2", partyPayload.get("toparty"));
    }

    @Test
    void parseSendResponseAcceptsSuccess() {
        NotificationSendResult result = WeComNotificationChannel.parseSendResponse("{\"errcode\":0,\"errmsg\":\"ok\"}");

        assertTrue(result.success());
    }

    @Test
    void parseSendResponseReportsInvalidRecipients() {
        NotificationSendResult result = WeComNotificationChannel.parseSendResponse(
                "{\"errcode\":0,\"errmsg\":\"ok\",\"invaliduser\":\"bad-user\",\"invalidparty\":\"9\",\"invalidtag\":\"8\",\"unlicenseduser\":\"u2\"}"
        );

        assertFalse(result.success());
        assertEquals("WeCom send errcode=0, ok, invaliduser=bad-user, invalidparty=9, invalidtag=8, unlicenseduser=u2", result.errorMessage());
    }

    @Test
    void sendBuildsTokenAndMessageRequests() {
        AtomicLong now = new AtomicLong(1000L);
        FakeHttpClient httpClient = new FakeHttpClient();
        httpClient.getResponses.add("{\"errcode\":0,\"access_token\":\"token-a\",\"expires_in\":7200}");
        httpClient.postResponses.add("{\"errcode\":0,\"errmsg\":\"ok\"}");
        WeComNotificationChannel channel = new WeComNotificationChannel(httpClient, now::get);

        NotificationChannel notificationChannel = new NotificationChannel();
        notificationChannel.setConfigJson("{\"corpId\":\"corp\",\"agentId\":\"100001\",\"toUser\":\"@all\",\"safe\":false}");
        notificationChannel.setSecretJson("{\"corpSecret\":\"secret\"}");
        NotificationMessage message = new NotificationMessage();
        message.setContent("content");

        NotificationSendResult result = channel.send(notificationChannel, message);

        assertTrue(result.success());
        assertEquals(1, httpClient.getCalls);
        assertEquals(1, httpClient.postCalls);
        assertTrue(httpClient.getUrls.get(0).contains("corpid=corp"));
        assertTrue(httpClient.getUrls.get(0).contains("corpsecret=secret"));
        assertTrue(httpClient.postUrls.get(0).contains("access_token=token-a"));
        assertEquals("@all", httpClient.lastPayload.get("touser"));
        assertEquals(Map.of("content", "content"), httpClient.lastPayload.get("text"));
    }

    private static class FakeHttpClient implements WeComNotificationChannel.WeComHttpClient {
        private final List<String> getResponses = new ArrayList<>();
        private final List<String> postResponses = new ArrayList<>();
        private final List<String> getUrls = new ArrayList<>();
        private final List<String> postUrls = new ArrayList<>();
        private int getCalls;
        private int postCalls;
        private Map<String, Object> lastPayload;

        @Override
        public String get(String url) {
            getUrls.add(url);
            return getResponses.get(getCalls++);
        }

        @Override
        public String postJson(String url, Map<String, Object> payload) {
            postUrls.add(url);
            lastPayload = payload;
            return postResponses.get(postCalls++);
        }
    }
}
