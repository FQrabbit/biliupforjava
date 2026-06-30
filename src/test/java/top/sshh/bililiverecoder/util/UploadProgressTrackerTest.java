package top.sshh.bililiverecoder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UploadProgressTrackerTest {

    @Test
    void startShouldPreserveExistingChunkProgressWhenRetryingSamePart() {
        UploadProgressTracker tracker = new UploadProgressTracker();

        tracker.start(1L, 2L, 3, 10, 1024L, 10_240L, "LEGACY");
        tracker.updateChunkDone(1L, 2L, 3, 4, 10);
        tracker.markRetryWait(1L, "平台限速，等待重试", 1, 3000L);

        tracker.start(1L, 2L, 3, 10, 1024L, 10_240L, "LEGACY");

        assertEquals(4, tracker.getByPartId(1L).getChunkDone());
        assertEquals(40, tracker.getByPartId(1L).getPercent());
    }
}
