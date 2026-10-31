package top.sshh.bililiverecoder.protection;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.notification.*;
import top.sshh.bililiverecoder.service.SystemConfigService;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

@Component
public class DiskProtectionNotifications {
    private final NotificationRuleEngine rules;
    private final SystemConfigService config;
    private final Map<String, NotificationChannelAdapter> adapters = new HashMap<>();
    private volatile List<NotificationChannel> channels = List.of();
    private static final ExecutorService sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> DiskSpaceGuard.daemon(r, "disk-protection-notify"), new ThreadPoolExecutor.DiscardPolicy());

    public DiskProtectionNotifications(NotificationRuleEngine rules, SystemConfigService config,
                                        List<NotificationChannelAdapter> adapters) {
        this.rules = rules; this.config = config;
        for (NotificationChannelAdapter adapter : adapters) this.adapters.put(adapter.type(), adapter);
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void refresh() {
        if (DiskSpaceGuard.blocksBusiness()) return;
        loadChannels();
    }

    private void loadChannels() {
        if (!config.getAllConfigsMap().getOrDefault(SystemConfigService.KEY_NOTIFICATION_ENABLED, "true").equalsIgnoreCase("true")) {
            channels = List.of(); return;
        }
        channels = rules.resolveChannels(null, NotificationEventType.WORKSPACE_USAGE_ALERT).stream().map(channel -> {
            NotificationChannel copy = new NotificationChannel();
            copy.setType(channel.getType()); copy.setConfigJson(channel.getConfigJson());
            copy.setSecretJson(channel.getSecretJson()); copy.setName(channel.getName());
            return copy;
        }).toList();
    }

    public Consumer<String> snapshotSender() {
        loadChannels();
        return text -> {
            List<NotificationChannel> snapshot = channels;
            sender.execute(() -> {
                NotificationMessage message = new NotificationMessage();
                message.setEventType(NotificationEventType.WORKSPACE_USAGE_ALERT);
                message.setTitle("biliupforjava 磁盘空间保护"); message.setContent(text);
                for (NotificationChannel channel : snapshot) {
                    try {
                        NotificationChannelAdapter adapter = adapters.get(channel.getType());
                        if (adapter != null) adapter.send(channel, message);
                    } catch (Exception ignored) { }
                }
            });
        };
    }
}
