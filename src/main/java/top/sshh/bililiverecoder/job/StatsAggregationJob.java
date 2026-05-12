package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.service.StatsAggregationService;

@Slf4j
@Component
public class StatsAggregationJob {

    @Autowired
    private StatsAggregationService statsAggregationService;

    @Scheduled(fixedDelay = 3600000, initialDelay = 20000)
    public void refreshRecentStats() {
        statsAggregationService.refreshRecentCompletedHistoriesAsync(200);
    }
}
