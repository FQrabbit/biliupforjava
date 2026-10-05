package top.sshh.bililiverecoder.job;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.service.StatsUpdateService;

@Component
@RequiredArgsConstructor
public class StatsAggregationJob {
    private final StatsUpdateService updates;

    @Scheduled(fixedDelay = 5000, initialDelay = 20000)
    public void updatePendingStats() { updates.wake(); }

    @Scheduled(fixedDelay = 3600000, initialDelay = 20000)
    public void inspectHistoricalStats() { updates.startScan(); }
}
