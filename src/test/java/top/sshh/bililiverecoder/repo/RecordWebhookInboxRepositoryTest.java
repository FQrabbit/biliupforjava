package top.sshh.bililiverecoder.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxEvent;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:webhook-inbox-order;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecordWebhookInboxRepositoryTest {
    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RecordWebhookInboxRepository repository;

    @Test
    void delayedEarlierEventBlocksLaterEventsOnlyForItsRoom() {
        LocalDateTime now = LocalDateTime.now();
        RecordWebhookInboxEvent delayed = create("room-a", "PENDING", now.plusMinutes(5), "event-a-1");
        RecordWebhookInboxEvent laterSameRoom = create("room-a", "PENDING", now.minusSeconds(1), "event-a-2");
        RecordWebhookInboxEvent otherRoom = create("room-b", "PENDING", now.minusSeconds(1), "event-b-1");
        entityManager.flush();
        entityManager.clear();

        List<Long> dueHeads = repository.findDueHeadEvents("PENDING", now, PageRequest.of(0, 20))
                .stream().map(RecordWebhookInboxEvent::getId).toList();
        assertEquals(List.of(otherRoom.getId()), dueHeads);

        delayed.setState("DONE");
        repository.save(delayed);
        entityManager.flush();
        entityManager.clear();

        List<Long> unblockedHeads = repository.findDueHeadEvents("PENDING", now, PageRequest.of(0, 20))
                .stream().map(RecordWebhookInboxEvent::getId).toList();
        assertEquals(List.of(laterSameRoom.getId(), otherRoom.getId()), unblockedHeads);
    }

    private RecordWebhookInboxEvent create(String lockKey, String state, LocalDateTime nextAttemptAt,
                                            String eventKey) {
        LocalDateTime now = LocalDateTime.now();
        RecordWebhookInboxEvent event = new RecordWebhookInboxEvent();
        event.setEventKey(eventKey);
        event.setLockKey(lockKey);
        event.setEventType("FileClosed");
        event.setSource("blrec");
        event.setDelayMs(0);
        event.setPayload("{}");
        event.setState(state);
        event.setAttemptCount(0);
        event.setNextAttemptAt(nextAttemptAt);
        event.setReceivedAt(now);
        event.setUpdatedAt(now);
        return repository.save(event);
    }
}
