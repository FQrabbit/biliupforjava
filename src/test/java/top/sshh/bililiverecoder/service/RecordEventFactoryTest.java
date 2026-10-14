package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordEventDTO;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordEventFactoryTest {
    private final RecordEventFactory factory = new RecordEventFactory();

    @Test
    void missingEventTypeIsReportedToDurableWebhookInbox() {
        assertThrows(IllegalArgumentException.class, () -> factory.processing(new RecordEventDTO()));
    }

    @Test
    void unsupportedEventTypeCanBeIgnoredWithoutBlockingInbox() {
        RecordEventDTO event = new RecordEventDTO();
        event.setEventType("UnknownRecorderEvent");

        assertTrue(factory.isUnsupportedEvent(event));
        factory.processing(event);
    }

    @Test
    void legacyBlrecPreservesLiveStatusForTimeBasedRecordingLifecycle() {
        var started = org.mockito.Mockito.mock(top.sshh.bililiverecoder.service.impl.RecordEventRecordStartedService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(factory, "recordEventRecordStartedService", started);
        RecordEventDTO event = new RecordEventDTO();
        event.setType(top.sshh.bililiverecoder.entity.RecordEventType.RecordingStartedEvent);
        event.setId("legacy-start");
        var data = new top.sshh.bililiverecoder.entity.BlrecData(); data.setRoomId("100");
        var info = new top.sshh.bililiverecoder.entity.BlrecRoomInfo();
        info.setRoomId("100"); info.setUid("123"); info.setLiveStatus(1); data.setRoomInfo(info); event.setData(data);
        factory.processing(event);
        assertTrue(event.getEventData().isStreaming());
        org.mockito.Mockito.verify(started).processing(event);
    }
}
