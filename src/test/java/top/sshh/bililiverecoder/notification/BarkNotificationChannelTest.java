package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BarkNotificationChannelTest {

    @Test
    void resolveEndpointUsesDefaultServerForPlainKey() {
        BarkNotificationChannel.BarkEndpoint endpoint = BarkNotificationChannel.resolveEndpoint("", "abc123");

        assertEquals("https://api.day.app/push", endpoint.pushUrl());
        assertEquals("abc123", endpoint.deviceKey());
    }

    @Test
    void resolveEndpointExtractsKeyFromCopiedTestUrl() {
        BarkNotificationChannel.BarkEndpoint endpoint = BarkNotificationChannel.resolveEndpoint(
                "",
                "https://bark.example.com/my-key/Test%20Title/Test%20Body?group=test"
        );

        assertEquals("https://bark.example.com/push", endpoint.pushUrl());
        assertEquals("my-key", endpoint.deviceKey());
    }

    @Test
    void resolveEndpointAddsHttpsForServerWithoutScheme() {
        BarkNotificationChannel.BarkEndpoint endpoint = BarkNotificationChannel.resolveEndpoint("api.day.app", "abc123");

        assertEquals("https://api.day.app/push", endpoint.pushUrl());
        assertEquals("abc123", endpoint.deviceKey());
    }

    @Test
    void buildPayloadIncludesRequiredAndOptionalFields() {
        JSONObject config = new JSONObject();
        config.put("group", "ops");
        config.put("sound", "minuet");
        config.put("icon", "https://example.com/icon.png");
        config.put("level", "timeSensitive");

        NotificationMessage message = new NotificationMessage();
        message.setTitle("Live started");
        message.setContent("Live started\nRoom: 1001");

        Map<String, Object> payload = BarkNotificationChannel.buildPayload("key", config, message);

        assertEquals("key", payload.get("device_key"));
        assertEquals("Live started", payload.get("title"));
        assertEquals("Live started\nRoom: 1001", payload.get("body"));
        assertEquals("ops", payload.get("group"));
        assertEquals("minuet", payload.get("sound"));
        assertEquals("https://example.com/icon.png", payload.get("icon"));
        assertEquals("timeSensitive", payload.get("level"));
    }

    @Test
    void buildPayloadFallsBackToSafeDefaults() {
        Map<String, Object> payload = BarkNotificationChannel.buildPayload("key", new JSONObject(), new NotificationMessage());

        assertEquals("biliupforjava", payload.get("title"));
        assertEquals("biliupforjava", payload.get("group"));
        assertEquals("active", payload.get("level"));
    }

    @Test
    void parseResponseAcceptsBarkSuccessCode() {
        NotificationSendResult result = BarkNotificationChannel.parseResponse("{\"code\":200,\"message\":\"success\"}");

        assertTrue(result.success());
    }

    @Test
    void parseResponseRejectsNonSuccessCode() {
        NotificationSendResult result = BarkNotificationChannel.parseResponse("{\"code\":400,\"message\":\"bad key\"}");

        assertFalse(result.success());
        assertEquals("Bark code=400, bad key", result.errorMessage());
    }
}
