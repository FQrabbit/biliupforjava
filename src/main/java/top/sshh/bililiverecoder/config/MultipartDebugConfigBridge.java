package top.sshh.bililiverecoder.config;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.util.bili.upload.MultipartDebugSupport;

@Component
public class MultipartDebugConfigBridge {

    private final Environment environment;

    public MultipartDebugConfigBridge(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void applyFromSpringConfig() {
        String sourceKey = null;
        String raw = null;
        if (environment.containsProperty("record.upload.multipart-debug")) {
            sourceKey = "record.upload.multipart-debug";
            raw = environment.getProperty(sourceKey);
        } else if (environment.containsProperty("blr.multipart.debug")) {
            sourceKey = "blr.multipart.debug";
            raw = environment.getProperty(sourceKey);
        }

        if (sourceKey == null) {
            return;
        }

        boolean enabled = MultipartDebugSupport.parseTruthy(StringUtils.defaultString(raw));
        MultipartDebugSupport.setEnabledFromConfig(enabled);
    }
}
