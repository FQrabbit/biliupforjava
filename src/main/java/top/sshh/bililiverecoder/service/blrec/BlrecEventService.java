package top.sshh.bililiverecoder.service.blrec;

import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;

public interface BlrecEventService {
    void processing(BlrecEventDTO event);
}
