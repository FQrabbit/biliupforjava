package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BlrecData;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.service.RecordEventFactory;

@Slf4j
@RestController
@RequestMapping("/recordWebHook")
public class RecordWebHook {

    @Autowired
    private RecordEventFactory recordEventFactory;

    @PostMapping
    public void processing(@RequestBody RecordEventDTO recordEvent) {
        String lock = "";
        if (recordEvent.getData() != null) {
            BlrecData data = recordEvent.getData();
            if (data.getRoomInfo() != null) {
                lock = "blrec:" + data.getRoomInfo().getRoomId();
            } else {
                lock = "blrec:" + data.getRoomId();
            }
        } else if (recordEvent.getEventData() != null) {
            try {
                if ("SessionEnded".equals(recordEvent.getEventType())) {
                    // 录制结束事件要单独线程进行延迟处理，防止直播结束先于录制结束事件处理
                    Thread.sleep(10000L);
                    lock = "brec:" + recordEvent.getEventType();
                } else if ("FileClosed".equals(recordEvent.getEventType())) {
                    // 录制结束事件要单独线程进行延迟处理，防止下一p文件打开先于文件关闭事件处理
                    lock = "brec:" + recordEvent.getEventData().getRelativePath();
                } else if ("FileOpening".equals(recordEvent.getEventType())) {
                    // 录制结束事件要单独线程进行延迟处理，防止下一p文件打开先于文件关闭事件处理
                    lock = "brec:" + recordEvent.getEventData().getRelativePath();
                } else {
                    lock = "brec:" + recordEvent.getEventData().getSessionId();
                }
            } catch (Exception e) {
            }
        }
        synchronized (lock.intern()) {
            if (recordEvent.getEventData() != null) {
                log.info("[WEBHOOK] 收到录播姬推送 | Type: {} | RoomId: {} | Title: {}", 
                        recordEvent.getEventType(), 
                        recordEvent.getEventData().getRoomId(), 
                        recordEvent.getEventData().getTitle());
                log.debug("[WEBHOOK_DEBUG] Full Payload: {}", JSON.toJSONString(recordEvent));
            } else {
                log.info("收到录播姬的推送信息(旧版/未知格式)==> {}", JSON.toJSONString(recordEvent));
            }
            recordEventFactory.processing(recordEvent);
        }
    }

    @GetMapping
    public String processing() {
        return "这里是录播姬推送的接口地址，把当前地址复制到录播姬WebHookV2里即可(前提是录播姬网络环境也可访问)";
    }
}
