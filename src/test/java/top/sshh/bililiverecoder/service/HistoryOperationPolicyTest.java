package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.PublishTaskState;
import top.sshh.bililiverecoder.entity.PublishTaskStatusDto;
import top.sshh.bililiverecoder.entity.RecordHistory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryOperationPolicyTest {
    private RecordHistory published() {
        RecordHistory history = new RecordHistory();
        history.setPublish(true);
        history.setBvId("BV1TEST");
        history.setCode(0);
        history.setPartCount(2);
        history.setRoomSendDm(true);
        history.setRoomSendSc(false);
        history.setRoomSendGiftReply(false);
        return history;
    }

    private void allowed(RecordHistory history, String action) {
        assertEquals("", HistoryOperationPolicy.disabledReason(history, action));
    }

    private void blocked(RecordHistory history, String action) {
        assertFalse(HistoryOperationPolicy.disabledReason(history, action).isEmpty());
    }

    @Test
    void danmakuRequiresOnlineIdentityAndAcceptedReview() {
        for (String action : List.of("reloadHistoryMsg", "deleteHistoryMsg", "retryFailedDanmaku", "abandonHistoryMsgQueue")) {
            RecordHistory history = published();
            history.setPublish(false);
            blocked(history, action);
            history.setPublish(true);
            history.setBvId(null);
            blocked(history, action);
            history.setBvId("BV1TEST");
            history.setCode(-10);
            blocked(history, action);
        }
    }

    @Test
    void reloadCanRecoverMissingDataButAbandonAndRetryRequireActualWork() {
        RecordHistory history = published();
        allowed(history, "reloadHistoryMsg");
        blocked(history, "deleteHistoryMsg");
        blocked(history, "abandonHistoryMsgQueue");
        blocked(history, "retryFailedDanmaku");
        history.setMsgCount(10);
        allowed(history, "deleteHistoryMsg");
        history.setFailedMsgCount(2);
        allowed(history, "retryFailedDanmaku");
        history.setPendingNormalMsgCount(3);
        allowed(history, "abandonHistoryMsgQueue");
        history.setRoomSendDm(false);
        blocked(history, "abandonHistoryMsgQueue");
        blocked(history, "reloadHistoryMsg");
    }

    @Test
    void privateSubmissionRetainsAdvancedDanmakuAndReplySupport() {
        RecordHistory history = published();
        history.setCode(-50);
        history.setPendingNormalMsgCount(2);
        history.setFailedMsgCount(1);
        blocked(history, "abandonHistoryMsgQueue");
        blocked(history, "retryFailedDanmaku");
        history.setRoomSendSc(true);
        history.setPendingHighMsgCount(1);
        allowed(history, "abandonHistoryMsgQueue");
        allowed(history, "retryFailedDanmaku");
        allowed(history, "reloadHistoryMsg");
        history.setRoomSendSc(false);
        history.setRoomSendGiftReply(true);
        history.setGiftReplyLineCount(2);
        allowed(history, "abandonHistoryMsgQueue");
        history.setSendReply(true);
        blocked(history, "abandonHistoryMsgQueue");
    }

    @Test
    void recordingAndRepairControlsMatchTheirActualPurpose() {
        RecordHistory history = published();
        blocked(history, "updatePartStatus");
        blocked(history, "rePublish");
        blocked(history, "updatePublishStatus");
        allowed(history, "highEnergyCutPublish");
        history.setRecordPartCount(1);
        allowed(history, "updatePartStatus");
        blocked(history, "highEnergyCutPublish");
        history.setRecordPartCount(0);
        history.setCode(-20);
        allowed(history, "rePublish");
        history.setCode(0);
        history.setGiveUpPartTypes(List.of("TIMESTAMP_JUMP"));
        allowed(history, "rePublish");
        history.setPublish(false);
        history.setBvId(null);
        allowed(history, "updatePublishStatus");
    }

    @Test
    void activeOrdinaryTaskProtectsIdentityButClipKeepsItsIndependentQueue() {
        RecordHistory history = published();
        PublishTaskStatusDto task = new PublishTaskStatusDto();
        task.setOperation(PublishTaskOperation.UPDATE);
        task.setState(PublishTaskState.SUBMITTING);
        history.setPublishTasks(List.of(task));
        blocked(history, "reloadHistoryMsg");
        blocked(history, "updatePublishStatus");
        allowed(history, "highEnergyCutPublish");
        task.setOperation(PublishTaskOperation.HIGH_ENERGY);
        blocked(history, "highEnergyCutPublish");
        allowed(history, "reloadHistoryMsg");
        history.setDeletePending(true);
        assertTrue(HistoryOperationPolicy.disabledReason(history, "reloadHistoryMsg").contains("删除"));
    }
}
