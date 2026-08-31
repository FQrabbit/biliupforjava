package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.LogAlert;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogAnalyzeServiceTest {

    @Test
    void groupsStructuredWarningsAcrossChunks() {
        LogAnalyzeService service = new LogAnalyzeService();
        String first = "[BLR] event=Upload.Chunk.Error | retryCategory=TIMEOUT | host=upload.example | chunkIndex=1 | msg=failed";
        String second = "[BLR] event=Upload.Chunk.Error | retryCategory=TIMEOUT | host=upload.example | chunkIndex=2 | msg=failed";

        service.processLog(first, "WARN");
        service.processLog(second, "WARN");

        List<LogAlert> alerts = service.getAlerts();
        assertEquals(1, alerts.size());
        assertEquals(2, alerts.get(0).getCount());
        assertEquals("Upload.Chunk.Error", alerts.get(0).getEvent());
        assertEquals("TIMEOUT", alerts.get(0).getRetryCategory());
        assertEquals("upload.example", alerts.get(0).getHost());
    }

    @Test
    void separatesDifferentHosts() {
        LogAnalyzeService service = new LogAnalyzeService();
        service.processLog("[BLR] event=Upload.Chunk.Error | retryCategory=TIMEOUT | host=a.example | msg=failed", "WARN");
        service.processLog("[BLR] event=Upload.Chunk.Error | retryCategory=TIMEOUT | host=b.example | msg=failed", "WARN");
        assertEquals(2, service.getAlerts().size());
    }
}
