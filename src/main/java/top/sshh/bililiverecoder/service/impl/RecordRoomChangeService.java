package top.sshh.bililiverecoder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.RecordEventService;

@Slf4j
@Component
public class RecordRoomChangeService implements RecordEventService {
    @Override
    public void processing(RecordEventDTO event) {

    }
}
