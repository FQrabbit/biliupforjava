package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.BiliLiveRecordApplication;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.TaskUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class CoreRestartService implements ApplicationContextAware {

    private static volatile String[] applicationArgs = new String[0];

    private final RecordRoomRepository roomRepository;
    private final DatabaseMaintenanceState maintenanceState;
    private final ShutdownState shutdownState;
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private volatile ConfigurableApplicationContext context;

    public CoreRestartService(RecordRoomRepository roomRepository,
                              DatabaseMaintenanceState maintenanceState,
                              ShutdownState shutdownState) {
        this.roomRepository = roomRepository;
        this.maintenanceState = maintenanceState;
        this.shutdownState = shutdownState;
    }

    public static void setApplicationArgs(String[] args) {
        applicationArgs = args == null ? new String[0] : args.clone();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        if (applicationContext instanceof ConfigurableApplicationContext configurable) {
            this.context = configurable;
        }
    }

    public Map<String, Object> requestRestart(boolean force) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (shutdownState.isShuttingDown()) {
            result.put("accepted", false);
            result.put("message", "核心正在关闭");
            return result;
        }
        if (restarting.get()) {
            result.put("accepted", false);
            result.put("restarting", true);
            result.put("message", "核心重启已经在进行中");
            return result;
        }
        List<String> blockers = blockers();
        result.put("blockers", blockers);
        if (!force && !blockers.isEmpty()) {
            result.put("accepted", false);
            result.put("requiresForce", true);
            result.put("message", "当前仍有任务正在运行");
            return result;
        }
        if (!restarting.compareAndSet(false, true)) {
            result.put("accepted", false);
            result.put("restarting", true);
            return result;
        }
        result.put("accepted", true);
        result.put("message", force ? "已确认强制重启核心" : "核心即将重启");
        Thread restartThread = new Thread(this::restart, "core-restart");
        restartThread.setDaemon(false);
        restartThread.start();
        return result;
    }

    private List<String> blockers() {
        List<String> blockers = new ArrayList<>();
        for (RecordRoom room : roomRepository.findAll()) {
            if (room != null && room.isRecording()) blockers.add("正在录制：" + room.getRoomId());
            if (room != null && room.isStreaming()) blockers.add("正在直播：" + room.getRoomId());
        }
        if (!TaskUtil.partUploadTask.isEmpty()) blockers.add("正在上传文件");
        if (!TaskUtil.publishTask.isEmpty()) blockers.add("正在投稿或发布");
        if (maintenanceState.isMaintenanceActive()) blockers.add("数据库维护正在执行");
        return blockers;
    }

    private void restart() {
        try {
            Thread.sleep(800L);
            ConfigurableApplicationContext oldContext = context;
            if (oldContext != null) oldContext.close();
            Throwable last = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    ConfigurableApplicationContext newContext =
                            BiliLiveRecordApplication.createSpringApplication().run(applicationArgs);
                    context = newContext;
                    log.info("核心重启完成，attempt={}", attempt);
                    restarting.set(false);
                    return;
                } catch (Throwable error) {
                    last = error;
                    log.error("核心重启失败，attempt={}", attempt, error);
                    Thread.sleep(1000L);
                }
            }
            log.error("核心重启最终失败，程序即将退出", last);
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("核心重启线程被中断", e);
            System.exit(1);
        }
    }

}
