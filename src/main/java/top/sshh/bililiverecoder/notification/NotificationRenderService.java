package top.sshh.bililiverecoder.notification;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NotificationRenderService {

    private final Map<NotificationEventType, NotificationMessageRenderer> renderers;

    public NotificationRenderService(List<NotificationMessageRenderer> renderers) {
        this.renderers = renderers.stream()
                .collect(Collectors.toMap(NotificationMessageRenderer::eventType, Function.identity()));
    }

    public NotificationMessage render(NotificationEvent event) {
        if (event == null || event.getEventType() == null) {
            return null;
        }
        NotificationMessageRenderer renderer = renderers.get(event.getEventType());
        return renderer == null ? null : renderer.render(event);
    }
}
