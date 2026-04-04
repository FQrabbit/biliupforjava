package top.sshh.bililiverecoder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import top.sshh.bililiverecoder.wizard.SetupWizardServer;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class BiliLiveRecordApplication {

    public static void main(String[] args) {
        // 在启动 Spring Boot 前拦截：如果没有配置文件且没有传入参数，则进入配置向导
        SetupWizardServer.checkAndRunWizardIfNeeded(args);

        SpringApplication.run(BiliLiveRecordApplication.class, args);
    }

}
