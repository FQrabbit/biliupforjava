package top.sshh.bililiverecoder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    @Bean("myAsyncPool")
    public TaskExecutor myAsyncThreadPool() {
        return new SimpleAsyncTaskExecutor();
    }

    @Bean("webhookExecutor")
    public TaskExecutor webhookExecutor(
            @Value("${record.webhook.core-pool-size:8}") int corePoolSize,
            @Value("${record.webhook.max-pool-size:16}") int maxPoolSize,
            @Value("${record.webhook.queue-capacity:2000}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("webhook-");
        // 队列满时不要把压力回传到 servlet 线程；由调度器选择返回 503 让发送方重试
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("webhookTaskScheduler")
    public ThreadPoolTaskScheduler webhookTaskScheduler(
            @Value("${record.webhook.scheduler-pool-size:1}") int poolSize
    ) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix("webhook-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
