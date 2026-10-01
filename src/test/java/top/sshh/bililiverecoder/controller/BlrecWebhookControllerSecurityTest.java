package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;
import top.sshh.bililiverecoder.service.WebhookRequestGuard;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BlrecWebhookControllerSecurityTest {
    @Test
    void remoteSourceNeedsTokenAndAcceptedEventsEnterDurableInbox() {
        DatabaseMaintenanceService maintenance = mock(DatabaseMaintenanceService.class);
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        when(inbox.accept(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new RecordWebhookInboxService.Acceptance(1L, false, "PENDING"));
        BlrecWebhookController controller = new BlrecWebhookController(maintenance, inbox,
                new WebhookRequestGuard("long-enough-webhook-token"));

        MockHttpServletRequest unauthorized = new MockHttpServletRequest();
        unauthorized.setRemoteAddr("192.0.2.15");
        unauthorized.setContent(payload().getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.UNAUTHORIZED, controller.handleWebhook(unauthorized).getStatusCode());

        MockHttpServletRequest accepted = new MockHttpServletRequest();
        accepted.setRemoteAddr("192.0.2.15");
        accepted.addHeader("X-Record-Webhook-Token", "long-enough-webhook-token");
        accepted.setContent(payload().getBytes(StandardCharsets.UTF_8));
        assertEquals(HttpStatus.OK, controller.handleWebhook(accepted).getStatusCode());
    }

    @Test
    void rejectsOversizedBodyBeforeParsing() {
        BlrecWebhookController controller = new BlrecWebhookController(mock(DatabaseMaintenanceService.class),
                mock(RecordWebhookInboxService.class), new WebhookRequestGuard(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setContent(new byte[1_048_577]);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, controller.handleWebhook(request).getStatusCode());
    }

    @Test
    void queryTokenIsRejectedByDefaultButBearerHeaderWorks() {
        String secret = "long-enough-webhook-token";
        WebhookRequestGuard guard = new WebhookRequestGuard(secret, 600, false);
        MockHttpServletRequest query = new MockHttpServletRequest();
        query.setRemoteAddr("192.0.2.15");
        query.setParameter("token", secret);
        assertEquals("UNAUTHORIZED", guard.rejectionReason(query));

        MockHttpServletRequest bearer = new MockHttpServletRequest();
        bearer.setRemoteAddr("192.0.2.15");
        bearer.addHeader("Authorization", "Bearer " + secret);
        assertEquals(null, guard.rejectionReason(bearer));
    }

    @Test
    void tokenlessRemoteRequestWithForwardingHeadersKeepsLegacyCompatibility() {
        WebhookRequestGuard guard = new WebhookRequestGuard("", 0, false);
        MockHttpServletRequest forwarded = new MockHttpServletRequest();
        forwarded.setRemoteAddr("192.0.2.15");
        forwarded.addHeader("X-Forwarded-For", "203.0.113.9");
        assertEquals(null, guard.rejectionReason(forwarded));
    }

    @Test
    void tokenlessRemoteBlrecWebhookKeepsEmptySuccessBodyForNewAndDuplicateEvents() {
        DatabaseMaintenanceService maintenance = mock(DatabaseMaintenanceService.class);
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        when(inbox.accept(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new RecordWebhookInboxService.Acceptance(1L, false, "PENDING"))
                .thenReturn(new RecordWebhookInboxService.Acceptance(1L, true, "DONE"));
        BlrecWebhookController controller = new BlrecWebhookController(maintenance, inbox,
                new WebhookRequestGuard(""));

        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("192.0.2.15");
        first.addHeader("X-Forwarded-For", "203.0.113.9");
        first.setContent(payload().getBytes(StandardCharsets.UTF_8));
        var accepted = controller.handleWebhook(first);

        MockHttpServletRequest duplicate = new MockHttpServletRequest();
        duplicate.setRemoteAddr("192.0.2.15");
        duplicate.setContent(payload().getBytes(StandardCharsets.UTF_8));
        var repeated = controller.handleWebhook(duplicate);

        assertEquals(HttpStatus.OK, accepted.getStatusCode());
        assertEquals(null, accepted.getBody());
        assertEquals(HttpStatus.OK, repeated.getStatusCode());
        assertEquals(null, repeated.getBody());
    }

    @Test
    void requestLimitIsOptIn() {
        WebhookRequestGuard guard = new WebhookRequestGuard("", 1, false);
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.setRemoteAddr("192.0.2.20");
        MockHttpServletRequest second = new MockHttpServletRequest();
        second.setRemoteAddr("192.0.2.20");
        assertEquals(null, guard.rejectionReason(first));
        assertEquals("RATE_LIMITED", guard.rejectionReason(second));
    }

    private static String payload() {
        return "{\"id\":\"evt-1\",\"type\":\"FileClosed\",\"data\":{\"room_id\":\"123\"}}";
    }
}
