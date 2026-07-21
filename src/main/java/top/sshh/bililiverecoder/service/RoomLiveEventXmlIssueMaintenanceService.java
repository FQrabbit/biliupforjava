package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@Service
public class RoomLiveEventXmlIssueMaintenanceService {

    private final RoomLiveEventXmlIssueService issueService;
    private final StatsAggregationService statsAggregationService;
    private final TaskExecutor taskExecutor;

    public RoomLiveEventXmlIssueMaintenanceService(RoomLiveEventXmlIssueService issueService,
                                                   StatsAggregationService statsAggregationService,
                                                   @Qualifier("myAsyncPool") TaskExecutor taskExecutor) {
        this.issueService = issueService;
        this.statsAggregationService = statsAggregationService;
        this.taskExecutor = taskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyIssuesAsync() {
        taskExecutor.execute(() -> {
            int migrated = issueService.migrateLegacyIssues();
            log.info("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.IssueMigration")
                    .add("migrated", migrated));
        });
    }

    @EventListener(StorageRootOnlineEvent.class)
    public void recheckRecoveredRoot(StorageRootOnlineEvent event) {
        if (event == null || event.rootId() == null) return;
        statsAggregationService.startXmlIssueRecheckByRoot(event.rootId());
    }
}
