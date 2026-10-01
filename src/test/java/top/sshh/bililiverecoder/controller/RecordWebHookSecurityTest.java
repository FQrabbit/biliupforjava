package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RecordWebHookSecurityTest {
    @Test
    void remoteRecorderMustProvideConfiguredToken() {
        RecordWebHook controller = new RecordWebHook(mock(RecordWebhookInboxService.class),
                mock(DatabaseMaintenanceService.class), "a-long-secret-token-value");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.15");
        request.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(HttpStatus.UNAUTHORIZED, controller.processing(request).getStatusCode());
    }

    @Test
    void noTokenConfigurationKeepsRemoteLegacyEndpointAndRejectsOversizedPayload() {
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        DatabaseMaintenanceService maintenance = mock(DatabaseMaintenanceService.class);
        org.mockito.Mockito.when(maintenance.spoolRecordWebhookIfMaintenance(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(false);
        org.mockito.Mockito.when(inbox.accept(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new RecordWebhookInboxService.Acceptance(1L, false, "PENDING"));
        RecordWebHook controller = new RecordWebHook(inbox, maintenance, "");
        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("192.0.2.15");
        remote.setContent("{\"EventId\":\"evt-1\",\"EventType\":\"FileClosed\",\"EventData\":{\"RoomId\":\"1\"}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ResponseEntity<String> accepted = controller.processing(remote);
        assertEquals(HttpStatus.OK, accepted.getStatusCode());
        assertEquals("OK", accepted.getBody());

        MockHttpServletRequest oversized = new MockHttpServletRequest();
        oversized.setRemoteAddr("127.0.0.1");
        oversized.setContent(new byte[1_048_577]);
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, controller.processing(oversized).getStatusCode());
    }

    @Test
    void maintenanceKeepsLegacyQueuedBody() {
        DatabaseMaintenanceService maintenance = mock(DatabaseMaintenanceService.class);
        org.mockito.Mockito.when(maintenance.spoolRecordWebhookIfMaintenance(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);
        RecordWebHook controller = new RecordWebHook(mock(RecordWebhookInboxService.class), maintenance, "");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.15");
        request.setContent("{\"EventId\":\"evt-1\",\"EventType\":\"FileClosed\",\"EventData\":{\"RoomId\":\"1\"}}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("QUEUED", controller.processing(request).getBody());
    }
}
