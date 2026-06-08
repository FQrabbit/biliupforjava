package top.sshh.bililiverecoder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    @Bean({"taskExecutor", "myAsyncPool"})
    public ThreadPoolTaskExecutor asyncThreadPool(
            @Value("${record.async.core-pool-size:8}") int corePoolSize,
            @Value("${record.async.max-pool-size:16}") int maxPoolSize,
            @Value("${record.async.queue-capacity:2000}") int queueCapacity,
            @Value("${record.async.await-termination-seconds:15}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, awaitTerminationSeconds));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("webhookExecutor")
    public TaskExecutor webhookExecutor(
            @Value("${record.webhook.core-pool-size:8}") int corePoolSize,
            @Value("${record.webhook.max-pool-size:16}") int maxPoolSize,
            @Value("${record.webhook.queue-capacity:2000}") int queueCapacity,
            @Value("${record.webhook.await-termination-seconds:5}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("webhook-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, awaitTerminationSeconds));
        // 队列满时不要把压力回传到 servlet 线程；由调度器选择返回 503 让发送方重试
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("webhookTaskScheduler")
    public ThreadPoolTaskScheduler webhookTaskScheduler(
            @Value("${record.webhook.scheduler-pool-size:1}") int poolSize,
            @Value("${record.webhook.scheduler-await-termination-seconds:2}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix("webhook-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(Math.max(0, awaitTerminationSeconds));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @Bean("danmakuExecutor")
    public TaskExecutor danmakuExecutor(
            @Value("${bili.dm.executor.core-pool-size:2}") int corePoolSize,
            @Value("${bili.dm.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${bili.dm.executor.queue-capacity:1000}") int queueCapacity,
            @Value("${bili.dm.executor.await-termination-seconds:5}") int awaitTerminationSeconds
    ) {
        return createTaskExecutor(
                Math.max(1, corePoolSize),
                Math.max(1, maxPoolSize),
                Math.max(0, queueCapacity),
                Math.max(0, awaitTerminationSeconds),
                "danmaku-reply-");
    }

    @Bean("danmakuHighExecutor")
    public TaskExecutor danmakuHighExecutor(
            @Value("${bili.dm.high-executor.core-pool-size:1}") int corePoolSize,
            @Value("${bili.dm.high-executor.max-pool-size:1}") int maxPoolSize,
            @Value("${bili.dm.high-executor.queue-capacity:200}") int queueCapacity,
            @Value("${bili.dm.high-executor.await-termination-seconds:5}") int awaitTerminationSeconds
    ) {
        return createTaskExecutor(
                Math.max(1, corePoolSize),
                Math.max(1, maxPoolSize),
                Math.max(0, queueCapacity),
                Math.max(0, awaitTerminationSeconds),
                "danmaku-high-");
    }

    @Bean("danmakuNormalExecutor")
    public TaskExecutor danmakuNormalExecutor(
            @Value("${bili.dm.normal-executor.core-pool-size:2}") int corePoolSize,
            @Value("${bili.dm.normal-executor.max-pool-size:4}") int maxPoolSize,
            @Value("${bili.dm.normal-executor.queue-capacity:1000}") int queueCapacity,
            @Value("${bili.dm.normal-executor.await-termination-seconds:5}") int awaitTerminationSeconds
    ) {
        return createTaskExecutor(
                Math.max(1, corePoolSize),
                Math.max(1, maxPoolSize),
                Math.max(0, queueCapacity),
                Math.max(0, awaitTerminationSeconds),
                "danmaku-normal-");
    }

    @Bean("danmakuTaskScheduler")
    public ThreadPoolTaskScheduler danmakuTaskScheduler(
            @Value("${bili.dm.scheduler-pool-size:1}") int poolSize,
            @Value("${bili.dm.scheduler-await-termination-seconds:2}") int awaitTerminationSeconds
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix("danmaku-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(Math.max(0, awaitTerminationSeconds));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    private ThreadPoolTaskExecutor createTaskExecutor(int corePoolSize,
                                                      int maxPoolSize,
                                                      int queueCapacity,
                                                      int awaitTerminationSeconds,
                                                      String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(Math.max(corePoolSize, maxPoolSize));
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
