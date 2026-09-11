package top.sshh.bililiverecoder.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditTimestampIssueResolutionTest {
    @Test
    void recountsRemainingIssuesThenClearsResolvedHistory() {
        var service = new RecordBiliPublishService();
        var repository = mock(RecordHistoryPartRepository.class);
        ReflectionTestUtils.setField(service, "partRepository", repository);
        var history = new RecordHistory();
        history.setId(3483L);
        history.setPublishIssueType("TIMESTAMP_JUMP");
        history.setPublishIssueReason("时间戳跳变");
        history.setPublishIssuePartCount(3);
        var bad = new RecordHistoryPart();
        bad.setDeleteFailType("TIMESTAMP_JUMP");
        when(repository.findByHistoryIdOrderByStartTimeAsc(3483L)).thenReturn(List.of(bad));
        ReflectionTestUtils.invokeMethod(service, "clearTimestampJumpIssueIfResolved", history);
        assertEquals(1, history.getPublishIssuePartCount());
        assertEquals("TIMESTAMP_JUMP", history.getPublishIssueType());
        assertNotNull(history.getPublishIssueReason());
        when(repository.findByHistoryIdOrderByStartTimeAsc(3483L)).thenReturn(List.of(new RecordHistoryPart()));
        ReflectionTestUtils.invokeMethod(service, "clearTimestampJumpIssueIfResolved", history);
        assertEquals(0, history.getPublishIssuePartCount());
        assertNull(history.getPublishIssueType());
        assertNull(history.getPublishIssueReason());
    }
}
