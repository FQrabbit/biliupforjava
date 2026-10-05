package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.service.impl.JdbcService;
import java.nio.file.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LiveMsgStatsNotificationTest {
    @TempDir Path directory;

    @Test
    void emptyTailAndEmptyXmlBothNotifyStatisticsAfterSuccessfulImport() throws Exception {
        for (int count : new int[]{0, 500, 1000, 501}) {
            var service = new LiveMsgService();
            var jdbc = mock(JdbcService.class);
            var stats = mock(StatsAggregationService.class);
            var histories = mock(RecordHistoryRepository.class);
            var rooms = mock(RecordRoomRepository.class);
            var locations = mock(PartFileLocationService.class);
            ReflectionTestUtils.setField(service, "jdbcService", jdbc);
            ReflectionTestUtils.setField(service, "statsAggregationService", stats);
            ReflectionTestUtils.setField(service, "recordHistoryRepository", histories);
            ReflectionTestUtils.setField(service, "liveMsgRepository", mock(LiveMsgRepository.class));
            ReflectionTestUtils.setField(service, "roomRepository", rooms);
            ReflectionTestUtils.setField(service, "partFileLocationService", locations);
            var room = new RecordRoom(); room.setRoomId("r");
            when(rooms.findByRoomId("r")).thenReturn(room);
            when(histories.findById(11L)).thenReturn(Optional.empty());
            var part = new RecordHistoryPart(); part.setId(12L); part.setHistoryId(11L); part.setRoomId("r");
            Path xml = directory.resolve("count-" + count + ".xml");
            StringBuilder data = new StringBuilder("<i>");
            for (int i=0;i<count;i++) data.append("<d p=\"1,1,25,16777215,0,0,0,0\">message").append(i).append("</d>");
            Files.writeString(xml, data.append("</i>"));
            when(locations.resolveCompanion(12L, ".xml")).thenReturn(Optional.of(xml));
            service.processing(part);
            verify(stats, times(1)).refreshHistoryStatsAsync(11L);
            verify(jdbc, times((count + 499) / 500)).saveLiveMsgList(anyList());
        }
    }
}
