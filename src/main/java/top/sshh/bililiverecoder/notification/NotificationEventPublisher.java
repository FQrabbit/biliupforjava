package top.sshh.bililiverecoder.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.Set;

@Slf4j
@Service
public class NotificationEventPublisher {

    private final NotificationRuleEngine ruleEngine;
    private final NotificationRenderService renderService;
    private final NotificationDispatchService dispatchService;
    private final SystemConfigService systemConfigService;
    private final TaskExecutor taskExecutor;

    public NotificationEventPublisher(NotificationRuleEngine ruleEngine,
                                      NotificationRenderService renderService,
                                      NotificationDispatchService dispatchService,
                                      SystemConfigService systemConfigService,
                                      @Qualifier("myAsyncPool") TaskExecutor taskExecutor) {
        this.ruleEngine = ruleEngine;
        this.renderService = renderService;
        this.dispatchService = dispatchService;
        this.systemConfigService = systemConfigService;
        this.taskExecutor = taskExecutor;
    }

    public void publish(NotificationEvent event, RecordRoom room) {
        if (event == null || !NotificationEventCatalog.isActive(event.getEventType()) || !isEnabled()) {
            return;
        }
        try {
            taskExecutor.execute(() -> dispatch(event, room));
        } catch (RuntimeException e) {
            log.warn("[BLR] {}", LogKvs.event("Notify.Event.Submit.Failed")
                    .addIfNotBlank("eventType", event.getEventType() == null ? null : event.getEventType().key())
                    .addIfNotBlank("roomId", event.getRoomId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    public void publishText(RecordRoom room, NotificationEventType eventType, String content) {
        NotificationEvent event = NotificationEvent.of(room, eventType)
                .add("content", content);
        publish(event, room);
    }

    private void dispatch(NotificationEvent event, RecordRoom room) {
        try {
            Set<NotificationChannel> channels = ruleEngine.resolveChannels(room, event.getEventType());
            if (channels.isEmpty()) {
                return;
            }
            NotificationMessage message = renderService.render(event);
            dispatchService.dispatch(message, channels);
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Notify.Event.Dispatch.Failed")
                    .addIfNotBlank("eventType", event.getEventType() == null ? null : event.getEventType().key())
                    .addIfNotBlank("roomId", event.getRoomId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private boolean isEnabled() {
        return systemConfigService.getAllConfigsMap()
                .getOrDefault(SystemConfigService.KEY_NOTIFICATION_ENABLED, "true")
                .equalsIgnoreCase("true");
    }
}
