package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;
import top.sshh.bililiverecoder.util.LogKvs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomLiveEventParseServiceTest {

    @Test
    void parseFailuresUseDistinctEventsAndStackTracePolicies() {
        assertLogPolicy(RoomLiveEventXmlIssue.IssueType.INVALID_XML,
                "RoomLiveEvent.Parse.InvalidXml",
                "XML 弹幕礼物文件格式错误，解析失败",
                false);
        assertLogPolicy(RoomLiveEventXmlIssue.IssueType.READ_FAILED,
                "RoomLiveEvent.Parse.ReadFailed",
                "XML 弹幕礼物文件读取失败",
                false);
        assertLogPolicy(RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR,
                "RoomLiveEvent.Parse.InternalError",
                "处理 XML 弹幕礼物数据时发生程序内部错误",
                true);
    }

    private void assertLogPolicy(RoomLiveEventXmlIssue.IssueType issueType,
                                 String expectedEvent,
                                 String expectedMessage,
                                 boolean expectedStackTrace) {
        String event = RoomLiveEventParseService.parseFailureEvent(issueType);

        assertEquals(expectedEvent, event);
        assertTrue(LogKvs.event(event).toString().contains("msg=" + expectedMessage));
        if (expectedStackTrace) {
            assertTrue(RoomLiveEventParseService.shouldLogFailureStackTrace(issueType));
        } else {
            assertFalse(RoomLiveEventParseService.shouldLogFailureStackTrace(issueType));
        }
    }
}
