package top.sshh.bililiverecoder.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class ScheduleConfig implements SchedulingConfigurer {
    @Bean("businessTaskScheduler")
    public ThreadPoolTaskScheduler businessTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new top.sshh.bililiverecoder.protection.ProtectionTaskScheduler();
        scheduler.setPoolSize(5);
        scheduler.setThreadNamePrefix("business-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setTaskScheduler(businessTaskScheduler());
    }
}
