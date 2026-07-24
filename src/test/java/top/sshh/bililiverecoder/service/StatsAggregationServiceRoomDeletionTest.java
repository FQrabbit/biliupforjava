package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sshh.bililiverecoder.repo.RoomLiveDailyStatsRepository;
import top.sshh.bililiverecoder.repo.RoomLiveDanmuUserStatsRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventParseStateRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventRepository;
import top.sshh.bililiverecoder.repo.RoomLiveGiftCatalogRepository;
import top.sshh.bililiverecoder.repo.RoomLiveMsgBucketStatsRepository;
import top.sshh.bililiverecoder.repo.RoomLiveSessionStatsRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsAggregationServiceRoomDeletionTest {

    @Mock
    private RoomLiveMsgBucketStatsRepository bucketStatsRepository;
    @Mock
    private RoomLiveDailyStatsRepository dailyStatsRepository;
    @Mock
    private RoomLiveSessionStatsRepository sessionStatsRepository;
    @Mock
    private RoomLiveDanmuUserStatsRepository danmuUserStatsRepository;
    @Mock
    private RoomLiveEventParseStateRepository eventParseStateRepository;
    @Mock
    private RoomLiveEventRepository eventRepository;
    @Mock
    private RoomLiveEventXmlIssueService xmlIssueService;
    @Mock
    private RoomLiveGiftCatalogRepository giftCatalogRepository;
    @Mock
    private RoomLiveGiftCatalogService giftCatalogService;
    @InjectMocks
    private StatsAggregationService service;

    @Test
    void deletesEveryRoomScopedStatisticsTable() {
        when(bucketStatsRepository.deleteByRoomId("123")).thenReturn(1);
        when(dailyStatsRepository.deleteByRoomId("123")).thenReturn(2);
        when(sessionStatsRepository.deleteByRoomId("123")).thenReturn(3);
        when(danmuUserStatsRepository.deleteByRoomId("123")).thenReturn(4);
        when(eventParseStateRepository.deleteByRoomId("123")).thenReturn(5);
        when(eventRepository.deleteByRoomId("123")).thenReturn(6);
        when(xmlIssueService.deleteByRoomId("123")).thenReturn(7L);
        when(giftCatalogRepository.deleteByRoomId("123")).thenReturn(8);

        StatsAggregationService.RoomStatsDeletionResult result = service.deleteRoomStats("123");

        assertEquals(36L, result.deletedTotal());
        assertEquals(36L, result.toMap().get("deletedTotalStatistics"));
        verify(bucketStatsRepository).deleteByRoomId("123");
        verify(dailyStatsRepository).deleteByRoomId("123");
        verify(sessionStatsRepository).deleteByRoomId("123");
        verify(danmuUserStatsRepository).deleteByRoomId("123");
        verify(eventParseStateRepository).deleteByRoomId("123");
        verify(eventRepository).deleteByRoomId("123");
        verify(xmlIssueService).deleteByRoomId("123");
        verify(giftCatalogRepository).deleteByRoomId("123");
        verify(giftCatalogService).clearRoomState("123");
    }

    @Test
    void blankRoomIdDoesNotDeleteStatistics() {
        StatsAggregationService.RoomStatsDeletionResult result = service.deleteRoomStats(" ");

        assertEquals(0L, result.deletedTotal());
        verifyNoInteractions(
                bucketStatsRepository,
                dailyStatsRepository,
                sessionStatsRepository,
                danmuUserStatsRepository,
                eventParseStateRepository,
                eventRepository,
                xmlIssueService,
                giftCatalogRepository,
                giftCatalogService);
    }
}
