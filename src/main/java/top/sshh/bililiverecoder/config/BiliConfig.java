package top.sshh.bililiverecoder.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import top.sshh.bililiverecoder.util.BiliApi;

import javax.annotation.PostConstruct;

@Configuration
public class BiliConfig {

    @Value("${bili.app-key}")
    private String appKey;

    @Value("${bili.app-secret}")
    private String appSecret;

    @PostConstruct
    public void init() {
        BiliApi.setAppKey(appKey);
        BiliApi.setAppSecret(appSecret);
    }
}
