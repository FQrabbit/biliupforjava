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
import static org.mockito.Mockito.mock;
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
        assertLogPolicy(RoomLiveEventXmlIssue.IssueType.RESOURCE_LIMIT,
                "RoomLiveEvent.Parse.ResourceLimit",
                "XML 文件超过当前解析资源上限，现有统计已保留",
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

    @Test
    void manuallyClearedCacheDoesNotCauseAutomaticXmlReimport() throws Exception {
        executeStatsWriteActionsImmediately();
        var states = mock(top.sshh.bililiverecoder.repo.RoomLiveEventParseStateRepository.class);
        var issues = mock(RoomLiveEventXmlIssueService.class);
        var locations = mock(PartFileLocationService.class);
        var users = mock(top.sshh.bililiverecoder.repo.RoomLiveDanmuUserStatsRepository.class);
        var updates = mock(StatsUpdateStore.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "parseStateRepository", states);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "xmlIssueService", issues);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "partFileLocationService", locations);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "danmuUserStatsRepository", users);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "statsUpdateStore", updates);
        var part = part(11L, 21L);
        part.setEndTime(java.time.LocalDateTime.now());
        when(partRepository.existsById(11L)).thenReturn(true);
        when(historyRepository.existsById(21L)).thenReturn(true);
        java.nio.file.Path xml = java.nio.file.Files.createTempFile("cleared-stats-", ".xml");
        try {
            java.nio.file.Files.writeString(xml, "<i><d>cached</d></i>");
            var state = new top.sshh.bililiverecoder.entity.RoomLiveEventParseState();
            state.setPartId(11L); state.setHistoryId(21L); state.setRoomId("123"); state.setSuccess(true);
            state.setDanmuCount(1); state.setXmlSize(java.nio.file.Files.size(xml));
            state.setXmlLastModified(java.nio.file.Files.getLastModifiedTime(xml).toMillis());
            state.setParserVersion(100);
            when(states.findByPartId(11L)).thenReturn(state);
            when(issues.find(11L)).thenReturn(java.util.Optional.empty());
            when(locations.resolveCompanionState(11L, ".xml")).thenReturn(new PartFileLocationService.CompanionResolution(
                    PartFileLocationService.CompanionState.AVAILABLE, xml, xml, null, null));
            when(updates.find(21L)).thenReturn(new StatsUpdateStore.State(21L,"123",0,0,0,0,true,"c","m","c","m",0,null,false,0));
            var result = service.parsePart(part, false);
            assertFalse(result.parsed());
            assertEquals("stats cache manually cleared", result.reason());
            verify(users, never()).existsByPartId(any());
            verify(states, never()).save(any());
        } finally { java.nio.file.Files.deleteIfExists(xml); }
    }

    private static RecordHistoryPart part(Long id, Long historyId) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setId(id);
        part.setHistoryId(historyId);
        part.setRoomId("123");
        return part;
    }
}
