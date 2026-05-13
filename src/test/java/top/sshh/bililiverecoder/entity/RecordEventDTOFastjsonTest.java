package top.sshh.bililiverecoder.entity;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.util.FastjsonWebhookDateDeserializer;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RecordEventDTOFastjsonTest {

    static {
        FastjsonWebhookDateDeserializer.registerGlobal();
    }

    @Test
    void shouldParseWebhookIsoOffsetDateWithSevenFractionDigits() {
        String payload = """
                {
                  "EventType": "FileClosed",
                  "EventTimestamp": "2026-05-13T13:59:07.2941442+08:00",
                  "EventData": {
                    "RoomId": "123",
                    "FileOpenTime": "2026-05-13T13:58:07.2941442+08:00",
                    "FileCloseTime": "2026-05-13T13:59:07.294664+08:00"
                  }
                }
                """;

        RecordEventDTO event = JSON.parseObject(payload, RecordEventDTO.class);

        assertNotNull(event.getEventTimestamp());
        assertNotNull(event.getEventData().getFileOpenTime());
        assertNotNull(event.getEventData().getFileCloseTime());
        assertEquals(
                OffsetDateTime.parse("2026-05-13T13:59:07.2941442+08:00").toInstant().toEpochMilli(),
                event.getEventTimestamp().getTime());
    }

    @Test
    void shouldKeepLegacyWebhookDateFormats() {
        String payload = """
                {
                  "date": "2026-05-13 13:59:07",
                  "type": "LiveBeganEvent",
                  "data": {}
                }
                """;

        RecordEventDTO event = JSON.parseObject(payload, RecordEventDTO.class);

        assertNotNull(event.getDate());
    }

    @Test
    void shouldParseBlrecWebhookDateWithIsoOffsetDate() {
        String payload = """
                {
                  "id": "event-id",
                  "date": "2026-05-13T13:59:07.2941442+08:00",
                  "type": "VideoFileCreatedEvent",
                  "data": {
                    "room_id": "123"
                  }
                }
                """;

        BlrecEventDTO event = JSON.parseObject(payload, BlrecEventDTO.class);

        assertNotNull(event.getDate());
        assertEquals(
                OffsetDateTime.parse("2026-05-13T13:59:07.2941442+08:00").toInstant().toEpochMilli(),
                event.getDate().getTime());
    }
}
