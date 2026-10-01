package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DatabaseMaintenanceWebhookSpoolTest {
    @TempDir
    Path tempDir;

    @Test
    void recoveredSpoolMovesIntoDurableInboxBeforeTheFileIsDeleted() throws Exception {
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        DatabaseMaintenanceService maintenance = maintenance(inbox);
        Path spool = writeSpool("/recordWebHook", "{\"id\":\"event-1\",\"type\":\"FileClosed\",\"data\":{}}",
                "brec:room:123");

        maintenance.recoverSpooledWebhooks();

        assertFalse(Files.exists(spool));
        verify(inbox).accept("{\"id\":\"event-1\",\"type\":\"FileClosed\",\"data\":{}}",
                "brec:room:123", 0L, "blrec", "FileClosed", "event-1");
    }

    @Test
    void databaseFailureKeepsSpoolForTheNextRecoveryAttempt() throws Exception {
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        when(inbox.accept(any(), any(), anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));
        DatabaseMaintenanceService maintenance = maintenance(inbox);
        Path spool = writeSpool("/webhook/blrec", "{\"id\":\"evt-2\",\"type\":\"FileClosed\",\"data\":{\"room_id\":\"123\"}}",
                "blrec:123");

        maintenance.recoverSpooledWebhooks();

        assertTrue(Files.exists(spool));
    }

    private DatabaseMaintenanceService maintenance(RecordWebhookInboxService inbox) {
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getBean(RecordWebhookInboxService.class)).thenReturn(inbox);
        DatabaseMaintenanceService service = new DatabaseMaintenanceService(mock(DataSource.class),
                mock(JdbcTemplate.class), mock(WebhookEventDispatcher.class), applicationContext,
                new DatabaseMaintenanceState(), mock(TaskExecutor.class));
        ReflectionTestUtils.setField(service, "workPath", tempDir.toString());
        return service;
    }

    private Path writeSpool(String endpoint, String payload, String lockKey) throws Exception {
        Path spoolDir = Files.createDirectories(tempDir.resolve("webhook-spool"));
        Path file = spoolDir.resolve("1-test.json");
        JSONObject item = new JSONObject(true);
        item.put("endpoint", endpoint);
        item.put("lockKey", lockKey);
        item.put("delayMs", 0L);
        item.put("createdAt", 1L);
        item.put("payloadBase64", Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(file, item.toJSONString(), StandardCharsets.UTF_8);
        return file;
    }
}
