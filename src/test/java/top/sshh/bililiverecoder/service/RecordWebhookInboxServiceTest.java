package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxEvent;
import top.sshh.bililiverecoder.repo.RecordWebhookInboxRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordWebhookInboxServiceTest {
    @Test
    void maintenanceSkipsInboxPollingAndPruningBeforeRepositoryAccess() {
        RecordWebhookInboxRepository repository = mock(RecordWebhookInboxRepository.class);
        DatabaseMaintenanceService maintenance = mock(DatabaseMaintenanceService.class);
        when(maintenance.isMaintenanceActive()).thenReturn(true);
        RecordWebhookInboxService service = new RecordWebhookInboxService(repository,
                mock(RecordEventFactory.class), mock(WebhookEventDispatcher.class), 20);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "databaseMaintenanceService", maintenance);
        service.dispatchDueEvents();
        service.pruneTerminalEvents();
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
    @Test
    void upstreamEventIdDeduplicatesReformattedPayloadButKeepsSourcesSeparate() {
        RecordWebhookInboxRepository repository = mock(RecordWebhookInboxRepository.class);
        Map<String, RecordWebhookInboxEvent> stored = new HashMap<>();
        AtomicLong nextId = new AtomicLong(1);
        when(repository.findByEventKey(anyString())).thenAnswer(call ->
                Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.saveAndFlush(any(RecordWebhookInboxEvent.class))).thenAnswer(call -> {
            RecordWebhookInboxEvent event = call.getArgument(0);
            event.setId(nextId.getAndIncrement());
            stored.put(event.getEventKey(), event);
            return event;
        });
        RecordWebhookInboxService service = new RecordWebhookInboxService(repository,
                mock(RecordEventFactory.class), mock(WebhookEventDispatcher.class), 20);

        RecordWebhookInboxService.Acceptance first = service.accept("{\"id\":\"evt-1\"}", "lock-a", 0,
                "blrec", "FileClosed", "evt-1");
        RecordWebhookInboxService.Acceptance retry = service.accept("{ \"id\" : \"evt-1\" }", "lock-a", 0,
                "blrec", "FileClosed", "evt-1");
        RecordWebhookInboxService.Acceptance otherSource = service.accept("{\"id\":\"evt-1\"}", "lock-a", 0,
                "brec", "FileClosed", "evt-1");

        assertFalse(first.alreadyQueued());
        assertTrue(retry.alreadyQueued());
        assertEquals(first.eventId(), retry.eventId());
        assertFalse(otherSource.alreadyQueued());
        assertNotEquals(first.eventId(), otherSource.eventId());
    }

    @Test
    void fallbackIdentityDeduplicatesExactPayload() {
        RecordWebhookInboxRepository repository = mock(RecordWebhookInboxRepository.class);
        Map<String, RecordWebhookInboxEvent> stored = new HashMap<>();
        AtomicLong nextId = new AtomicLong(1);
        when(repository.findByEventKey(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.saveAndFlush(any(RecordWebhookInboxEvent.class))).thenAnswer(call -> {
            RecordWebhookInboxEvent event = call.getArgument(0);
            event.setId(nextId.getAndIncrement());
            stored.put(event.getEventKey(), event);
            return event;
        });
        RecordWebhookInboxService service = new RecordWebhookInboxService(repository,
                mock(RecordEventFactory.class), mock(WebhookEventDispatcher.class), 20);

        service.accept("payload", "lock-a", 0, "blrec", "FileClosed");
        assertTrue(service.accept("payload", "lock-a", 0, "blrec", "FileClosed").alreadyQueued());
        assertFalse(service.accept("different", "lock-a", 0, "blrec", "FileClosed").alreadyQueued());
    }

    @Test
    void sameUpstreamEventIdInDifferentRoomsIsNotDeduplicated() {
        RecordWebhookInboxRepository repository = mock(RecordWebhookInboxRepository.class);
        Map<String, RecordWebhookInboxEvent> stored = new HashMap<>();
        AtomicLong nextId = new AtomicLong(1);
        when(repository.findByEventKey(anyString())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
        when(repository.saveAndFlush(any(RecordWebhookInboxEvent.class))).thenAnswer(call -> {
            RecordWebhookInboxEvent event = call.getArgument(0);
            event.setId(nextId.getAndIncrement());
            stored.put(event.getEventKey(), event);
            return event;
        });
        RecordWebhookInboxService service = new RecordWebhookInboxService(repository,
                mock(RecordEventFactory.class), mock(WebhookEventDispatcher.class), 20);

        var roomA = service.accept("payload-a", "blrec:100", 0, "blrec-native", "FileClosed", "evt-1");
        var roomB = service.accept("payload-b", "blrec:200", 0, "blrec-native", "FileClosed", "evt-1");

        assertFalse(roomA.alreadyQueued());
        assertFalse(roomB.alreadyQueued());
        assertNotEquals(roomA.eventId(), roomB.eventId());
    }
}
