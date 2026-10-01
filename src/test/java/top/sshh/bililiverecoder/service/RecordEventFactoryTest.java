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
}
