package top.sshh.bililiverecoder.service.impl;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecordBiliPublishServiceTemplateMapTest {

    private final RecordBiliPublishService service = new RecordBiliPublishService();

    @Test
    void historyTitleTemplateShouldNotUseLastPartLiveTitle() {
        RecordRoom room = room("HuoXi");
        RecordHistory history = history("崩铁锄学习中", LocalDateTime.of(2026, 7, 5, 21, 1));
        Map<String, Object> historyMap = service.buildHistoryTemplateMap(history, room);

        RecordHistoryPart firstPart = part("旧分P标题", LocalDateTime.of(2026, 7, 5, 21, 2));
        RecordHistoryPart lastPart = part("最新一期稿件标题", LocalDateTime.of(2026, 7, 6, 1, 3));

        String firstPartTitle = service.template("P${index}-${title}-${MM月dd日HH点mm分}",
                service.buildPartTemplateMap(historyMap, firstPart, 1)).getDesc();
        String lastPartTitle = service.template("P${index}-${title}-${MM月dd日HH点mm分}",
                service.buildPartTemplateMap(historyMap, lastPart, 2)).getDesc();
        String publishTitle = service.template("【直播回放】【${uname}】${title} ${yyyy年MM月dd日HH点mm分}", historyMap).getDesc();

        assertEquals("P1-旧分P标题-07月05日21点02分", firstPartTitle);
        assertEquals("P2-最新一期稿件标题-07月06日01点03分", lastPartTitle);
        assertEquals("【直播回放】【HuoXi】崩铁锄学习中 2026年07月05日21点01分", publishTitle);
    }

    @Test
    void historyTitleTemplateShouldFallbackWhenHistoryTitleBlank() {
        RecordRoom room = room("HuoXi");
        RecordHistory history = history(" ", LocalDateTime.of(2026, 7, 5, 21, 1));

        String publishTitle = service.template("${title}", service.buildHistoryTemplateMap(history, room)).getDesc();

        assertEquals("直播录像", publishTitle);
    }

    private static RecordRoom room(String uname) {
        RecordRoom room = new RecordRoom();
        room.setRoomId("21195828");
        room.setUname(uname);
        return room;
    }

    private static RecordHistory history(String title, LocalDateTime startTime) {
        RecordHistory history = new RecordHistory();
        history.setTitle(title);
        history.setStartTime(startTime);
        return history;
    }

    private static RecordHistoryPart part(String liveTitle, LocalDateTime startTime) {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setLiveTitle(liveTitle);
        part.setStartTime(startTime);
        part.setFilePath("recording.flv");
        return part;
    }
}
