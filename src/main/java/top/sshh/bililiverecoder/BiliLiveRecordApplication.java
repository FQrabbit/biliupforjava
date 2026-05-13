package top.sshh.bililiverecoder;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;

import org.springframework.context.annotation.ImportRuntimeHints;
import top.sshh.bililiverecoder.config.BiliupRuntimeHintsRegistrar;
import top.sshh.bililiverecoder.util.FastjsonWebhookDateDeserializer;
import top.sshh.bililiverecoder.wizard.SetupWizardServer;
import com.alibaba.fastjson.parser.ParserConfig;
import com.alibaba.fastjson.serializer.SerializeConfig;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@ImportRuntimeHints(BiliupRuntimeHintsRegistrar.class)
@RegisterReflectionForBinding({
    jakarta.servlet.GenericServlet.class,
    jakarta.servlet.http.HttpServlet.class
})
public class BiliLiveRecordApplication {

    public static void main(String[] args) {
        // 在 GraalVM Native Image 环境下，Fastjson 的 ASM 动态字节码生成功能会导致类加载失败
        // 必须强制关闭 ASM，退回到普通的反射模式
        ParserConfig.getGlobalInstance().setAsmEnable(false);
        FastjsonWebhookDateDeserializer.registerGlobal();
        SerializeConfig.getGlobalInstance().setAsmEnable(false);

        // 在启动 Spring Boot 前拦截：如果没有配置文件且没有传入参数，则进入配置向导
        SetupWizardServer.checkAndRunWizardIfNeeded(args);

        SpringApplication.run(BiliLiveRecordApplication.class, args);
    }

}
