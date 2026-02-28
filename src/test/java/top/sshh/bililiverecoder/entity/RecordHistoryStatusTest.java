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
}

