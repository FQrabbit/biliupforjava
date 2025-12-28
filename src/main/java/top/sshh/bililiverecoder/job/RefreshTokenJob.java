package top.sshh.bililiverecoder.job;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.service.impl.BiliBiliUserService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;

@Slf4j
@Component
public class RefreshTokenJob {

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private BiliBiliUserService userService;


    //两天更新一次
    @Scheduled(fixedDelay = 172800000, initialDelay = 60000)
    public void refreshTokenProcess() {
        LocalDateTime now = LocalDateTime.now().minusHours(1);
        Iterable<BiliBiliUser> all = userRepository.findAll();
        for (BiliBiliUser user : all) {
            LocalDateTime updateTime = user.getUpdateTime();
            if(updateTime.isAfter(now)){
                log.debug("[BLR] {}", LogKvs.event("RefreshTokenJob.SkipRecent")
                        .add("uid", user.getUid())
                        .add("uname", user.getUname()));
                continue;
            }
            try {
                // 避免请求过快，每次请求间隔5秒
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[BLR] {}", LogKvs.event("RefreshTokenJob.SleepInterrupted")
                        .add("waitMs", 5000)
                        .add("uid", user.getUid())
                        .add("uname", user.getUname()), e);
                return;
            }
            try {
                userService.refreshToken(user);
            } catch (Exception e){
                log.error("[BLR] {}", LogKvs.event("RefreshTokenJob.RefreshFailed")
                        .add("uid", user.getUid())
                        .add("uname", user.getUname())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
    }
}
