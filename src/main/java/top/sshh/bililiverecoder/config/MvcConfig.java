package top.sshh.bililiverecoder.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

@Configuration
public class MvcConfig implements WebMvcConfigurer{

    private final AsyncTaskExecutor taskExecutor;
    private final DatabaseMaintenanceState maintenanceState;

    public MvcConfig(@Qualifier("taskExecutor") AsyncTaskExecutor taskExecutor, DatabaseMaintenanceState maintenanceState) {
        this.taskExecutor = taskExecutor;
        this.maintenanceState = maintenanceState;
    }

    @Value("${record.userName}")
    private String userName;

    @Value("${record.password}")
    private String password;

    @Value("${record.mvc.async-timeout-ms:7200000}")
    private long mvcAsyncTimeoutMs;

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(taskExecutor);
        configurer.setDefaultTimeout(Math.max(30000L, mvcAsyncTimeoutMs));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LoginInterceptor loginInterceptor = new LoginInterceptor(userName,password);
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/recordWebHook",
                        "/webhook/blrec",
                        "/",
                        "/index.html",
                        "/html/**",
                        "/mobile",
                        "/mobile/",
                        "/mobile/**",
                        "/css/**",
                        "/js/**",
                        "/modules/**",
                        "/img/**",
                        "/ws/**",
                        "/favicon.ico",
                         "/.well-known/**",
                        "/error",
                        "/api/version",
                        "/api/version/check",
                        "/api/setup/**"
                );
        registry.addInterceptor(new DatabaseMaintenanceInterceptor(maintenanceState)).addPathPatterns("/**");
    }
}
