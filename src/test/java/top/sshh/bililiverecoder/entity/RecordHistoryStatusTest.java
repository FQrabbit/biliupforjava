package top.sshh.bililiverecoder.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordHistoryStatusTest {

    @Test
    void statusShouldBeCompletedWhenAllDanmakuSwitchesOff() {
        RecordHistory history = new RecordHistory();
        history.setUpload(true);
        history.setPublish(true);
        history.setCode(0);
        history.setSendReply(false);
        history.setRoomSendDm(false);
        history.setRoomSendSc(false);

        assertEquals("已完成", history.getStatus());
    }

    @Test
    void editPartsUploadShouldOverrideRejectedStatus() {
        RecordHistory history = new RecordHistory();
        history.setUpload(true);
        history.setPublish(true);
        history.setCode(-2);
        history.setForceArchived(true);
        history.setEditPartsUploading(true);

        assertEquals("分P上传中", history.getStatus());
        assertEquals(-2, history.getCode());
    }

    @Test
    void giftReplyWithoutCandidatesShouldNotKeepCompletedHistoryInSendingState() {
        RecordHistory history = new RecordHistory();
        history.setUpload(true);
        history.setPublish(true);
        history.setCode(0);
        history.setSendReply(false);
        history.setRoomSendDm(false);
        history.setRoomSendSc(false);
        history.setRoomSendGiftReply(true);
        history.setPendingHighMsgCount(0);

        assertEquals("已完成", history.getStatus());
    }
}
