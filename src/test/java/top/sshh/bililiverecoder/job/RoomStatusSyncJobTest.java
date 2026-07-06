package top.sshh.bililiverecoder.job;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomStatusSyncJobTest {

    @Test
    void shouldOnlyPublishLiveEndedAfterObservedLiveState() {
        RoomStatusSyncJob job = new RoomStatusSyncJob();

        assertFalse(job.shouldPublishLiveEnded(null, false));
        assertFalse(job.shouldPublishLiveEnded(null, true));
        assertFalse(job.shouldPublishLiveEnded(false, false));
        assertFalse(job.shouldPublishLiveEnded(false, true));
        assertFalse(job.shouldPublishLiveEnded(true, true));
        assertTrue(job.shouldPublishLiveEnded(true, false));
    }
}
