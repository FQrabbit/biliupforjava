package top.sshh.bililiverecoder.notification;

import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationDelivery;
import top.sshh.bililiverecoder.repo.NotificationDeliveryRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class NotificationDispatchService {

    private final NotificationDeliveryRepository notificationDeliveryRepository;
    private final Map<String, NotificationChannelAdapter> adapters;

    public NotificationDispatchService(NotificationDeliveryRepository notificationDeliveryRepository,
                                       List<NotificationChannelAdapter> adapters) {
        this.notificationDeliveryRepository = notificationDeliveryRepository;
        this.adapters = adapters.stream().collect(Collectors.toMap(NotificationChannelAdapter::type, Function.identity()));
    }

    public void dispatch(NotificationMessage message, Set<NotificationChannel> channels) {
        if (message == null || channels == null || channels.isEmpty()) {
            return;
        }
        for (NotificationChannel channel : channels) {
            sendToChannel(channel, message);
        }
    }

    public NotificationSendResult sendToChannel(NotificationChannel channel, NotificationMessage message) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setEventType(message.getEventType() == null ? null : message.getEventType().key());
        delivery.setEventLabel(message.getEventType() == null ? null : message.getEventType().label());
        delivery.setRoomId(message.getRoomId());
        delivery.setRoomName(message.getRoomName());
        delivery.setChannelId(channel.getId());
        delivery.setChannelType(channel.getType());
        delivery.setChannelName(channel.getName());
        delivery.setTitle(message.getTitle());
        delivery.setContent(message.getContent());
        delivery.setStatus("PENDING");
        delivery.setCreateTime(LocalDateTime.now());
        delivery = notificationDeliveryRepository.save(delivery);

        NotificationChannelAdapter adapter = adapters.get(channel.getType());
        NotificationSendResult result = adapter == null
                ? NotificationSendResult.failed("unsupported channel type: " + channel.getType())
                : adapter.send(channel, message);

        delivery.setSentTime(LocalDateTime.now());
        delivery.setStatus(result.success() ? "SUCCESS" : "FAILED");
        delivery.setErrorMessage(result.errorMessage());
        notificationDeliveryRepository.save(delivery);
        return result;
    }
}
