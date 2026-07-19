package top.sshh.bililiverecoder.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartFileCleanupPolicyTest {

    private final PartFileCleanupPolicy policy = new PartFileCleanupPolicy();

    @ParameterizedTest
    @CsvSource({
            "0, NONE",
            "1, AFTER_UPLOAD",
            "2, AFTER_AUDIT",
            "3, SCHEDULED",
            "4, AFTER_UPLOAD",
            "5, AFTER_AUDIT",
            "6, AFTER_RECORD_CLOSE",
            "7, AFTER_RECORD_CLOSE",
            "8, SCHEDULED",
            "9, AFTER_PUBLISH",
            "10, AFTER_PUBLISH",
            "11, AFTER_AUDIT"
    })
    void mapsEveryDeleteTypeToExactlyOneMilestone(
            int deleteType, PartFileCleanupPolicy.CleanupMilestone expected) {
        assertEquals(expected, policy.milestoneFor(deleteType));
        assertEquals(expected == PartFileCleanupPolicy.CleanupMilestone.AFTER_UPLOAD,
                policy.isPostUploadCleanupType(deleteType));
        assertEquals(expected == PartFileCleanupPolicy.CleanupMilestone.AFTER_AUDIT,
                policy.isPostAuditCleanupType(deleteType));
        assertEquals(expected == PartFileCleanupPolicy.CleanupMilestone.AFTER_RECORD_CLOSE,
                policy.isPostRecordCloseCleanupType(deleteType));
        assertEquals(expected == PartFileCleanupPolicy.CleanupMilestone.SCHEDULED,
                policy.isScheduledCleanupType(deleteType));
        assertEquals(expected == PartFileCleanupPolicy.CleanupMilestone.AFTER_PUBLISH,
                policy.isPostPublishCleanupType(deleteType));
    }
}
