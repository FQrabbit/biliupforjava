package top.sshh.bililiverecoder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.RecordEventService;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@Component
public class RecordEventEmptyService implements RecordEventService {

    @Override
    public void processing(RecordEventDTO event) {
        log.error("[BLR] {}", LogKvs.event("RecordEvent.Unsupported")
            .add("eventId", event.getEventId())
            .add("eventType", event.getEventType())
            .add("type", event.getType())
            .add("hasData", event.getData() != null)
            .add("hasEventData", event.getEventData() != null));
    }
}
