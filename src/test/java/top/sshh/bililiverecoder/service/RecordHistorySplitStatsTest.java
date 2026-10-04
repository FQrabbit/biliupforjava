package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.sshh.bililiverecoder.repo.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class RecordHistorySplitStatsTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfiguration.class);

    @Test
    void realAsyncStatsServiceIsResolvedOnlyAfterCommitAndRefreshesBothHistories() {
        AtomicInteger creations = new AtomicInteger();
        CountDownLatch refreshed = new CountDownLatch(2);
        List<Long> refreshedIds = new CopyOnWriteArrayList<>();
        List<String> workerNames = new CopyOnWriteArrayList<>();
        runner.withBean("statsAggregationService", StatsAggregationService.class, () -> {
                    creations.incrementAndGet();
                    return new StatsAggregationService();
                }, definition -> definition.setLazyInit(true))
                .withInitializer(context -> {
                    // 统计服务本身和异步代理都用真实对象，只替换它访问的数据库和解析依赖
                    for (var field : StatsAggregationService.class.getDeclaredFields()) {
                        if (field.isAnnotationPresent(Autowired.class) && field.getType() != TaskExecutor.class) {
                            context.getBeanFactory().registerSingleton(field.getName(), mock(field.getType()));
                        }
                    }
                    RecordHistoryRepository histories = context.getBeanFactory().getBean(RecordHistoryRepository.class);
                    when(histories.findById(anyLong())).thenAnswer(invocation -> {
                        refreshedIds.add(invocation.getArgument(0));
                        workerNames.add(Thread.currentThread().getName());
                        refreshed.countDown();
                        return Optional.empty();
                    });
                }).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(0, creations.get());
                    TransactionSynchronizationManager.initSynchronization();
                    try {
                        ReflectionTestUtils.invokeMethod(context.getBean(RecordHistorySplitService.class),
                                "refreshStatsAfterCommit", 11L, 12L);
                        assertEquals(0, creations.get());
                        assertTrue(refreshedIds.isEmpty());
                        for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
                        assertTrue(refreshed.await(10, TimeUnit.SECONDS));
                        assertEquals(1, creations.get());
                        assertTrue(AopUtils.isAopProxy(context.getBean(StatsAggregationService.class)));
                        assertEquals(List.of(11L, 12L), refreshedIds);
                        assertTrue(workerNames.stream().allMatch(name -> name.startsWith("split-stats-test-")));
                    } finally {
                        TransactionSynchronizationManager.clearSynchronization();
                    }
                });
    }

    @Test
    void missingOptionalStatsServiceDoesNotFailCommit() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            TransactionSynchronizationManager.initSynchronization();
            try {
                ReflectionTestUtils.invokeMethod(context.getBean(RecordHistorySplitService.class),
                        "refreshStatsAfterCommit", 11L, 12L);
                for (var callback : TransactionSynchronizationManager.getSynchronizations()) {
                    assertDoesNotThrow(callback::afterCommit);
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization();
            }
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync(proxyTargetClass = true)
    static class AsyncConfiguration {
        @Bean
        RecordHistorySplitService splitService() {
            return new RecordHistorySplitService(mock(RecordHistoryRepository.class),
                    mock(RecordHistoryPartRepository.class), mock(RecordRoomRepository.class),
                    mock(SystemConfigService.class), mock(PartPreviewService.class),
                    mock(PlatformTransactionManager.class), mock(RoomLiveEventRepository.class),
                    mock(RoomLiveDanmuUserStatsRepository.class), mock(RoomLiveEventParseStateRepository.class),
                    mock(RoomLiveEventXmlIssueRepository.class));
        }

        @Bean(name = "myAsyncPool", destroyMethod = "shutdown")
        ThreadPoolTaskExecutor statsExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("split-stats-test-");
            return executor;
        }
    }
}
