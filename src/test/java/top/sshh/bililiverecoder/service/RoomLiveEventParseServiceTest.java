package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoomLiveEventParseServiceTest {

    @Mock
    private RecordHistoryRepository historyRepository;
    @Mock
    private RecordHistoryPartRepository partRepository;
    @Mock
    private StatsAggregationService statsAggregationService;
    @InjectMocks
    private RoomLiveEventParseService service;

    @Test
    void deletedPartDoesNotRecreateStatistics() {
        executeStatsWriteActionsImmediately();
        RecordHistoryPart part = part(11L, 21L);
        when(partRepository.existsById(11L)).thenReturn(false);

        RoomLiveEventParseService.ParseResult result = service.parsePart(part, false);

        assertFalse(result.parsed());
        assertEquals("part deleted", result.reason());
        verify(historyRepository, never()).existsById(any());
    }

    @Test
    void deletedHistoryDoesNotRecreateStatistics() {
        executeStatsWriteActionsImmediately();
        RecordHistoryPart part = part(11L, 21L);
        when(partRepository.existsById(11L)).thenReturn(true);
        when(historyRepository.existsById(21L)).thenReturn(false);

        RoomLiveEventParseService.ParseResult result = service.parsePart(part, false);

        assertFalse(result.parsed());
        assertEquals("history deleted", result.reason());
    }

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

    private void executeStatsWriteActionsImmediately() {
        doAnswer(invocation -> {
            Supplier<?> action = invocation.getArgument(0);
            return action.get();
        }).when(statsAggregationService).withStatsWriteLock(any());
    }

    private static RecordHistoryPart part(Long id, Long historyId) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(historyId);
        part.setRoomId("123");
        return part;
    }
}
