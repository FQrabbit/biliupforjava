package top.sshh.bililiverecoder.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer{

    private final AsyncTaskExecutor taskExecutor;

    public MvcConfig(@Qualifier("taskExecutor") AsyncTaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor;
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
                        "/**/recordWebHook",
                        "/",
                        "/index.html",
                        "/html/**",
                        "/css/**",
                        "/js/**",
                        "/img/**",
                        "/ws/**",
                        "/favicon.ico",
                         "/.well-known/**",
                        "/error",
                        "/api/version",
                        "/api/version/check"
                );
    }
}
