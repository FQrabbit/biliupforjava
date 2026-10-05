package top.sshh.bililiverecoder.service;

import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatsIncrementalIntegrationTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withUserConfiguration(ConfigurationForTest.class)
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:stats-" + UUID.randomUUID(),
                        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.generate_statistics=true",
                        "logging.level.org.hibernate=ERROR");
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EntityScan("top.sshh.bililiverecoder.entity")
    @EnableJpaRepositories("top.sshh.bililiverecoder.repo")
    @Import({StatsUpdateStore.class, StatsResultWriter.class})
    static class ConfigurationForTest {}

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import(top.sshh.bililiverecoder.config.StatsRepositoryNotifications.class)
    static class NotificationConfiguration {}

    @Test
    void repositoryNotificationsIgnorePollAndDispatchFieldsButKeepRealChanges() {
        var notifications = org.mockito.Mockito.mock(StatsUpdateService.class);
        runner().withUserConfiguration(NotificationConfiguration.class)
                .withBean(StatsUpdateService.class, () -> notifications).run(context -> {
            assertNull(context.getStartupFailure());
            var histories = context.getBean(RecordHistoryRepository.class);
            var history = histories.save(StatsUpdateServiceTest.history(null));
            org.mockito.Mockito.verify(notifications).reconcile(history.getId());
            org.mockito.Mockito.clearInvocations(notifications);
            history.setUpdateTime(LocalDateTime.now()); histories.save(history);
            org.mockito.Mockito.verifyNoInteractions(notifications);
            history.setPublish(true); histories.save(history);
            org.mockito.Mockito.verify(notifications).reconcile(history.getId());
            var part = new RecordHistoryPart(); part.setHistoryId(history.getId());
            part = context.getBean(RecordHistoryPartRepository.class).save(part);
            var messages = context.getBean(LiveMsgRepository.class);
            var msg = new LiveMsg(); msg.setPartId(part.getId()); msg.setSendTime(1000L);
            msg = messages.save(msg);
            org.mockito.Mockito.verify(notifications).requestContent(history.getId());
            org.mockito.Mockito.clearInvocations(notifications);
            msg.setCode(0); msg.setSend(true); messages.save(msg);
            org.mockito.Mockito.verifyNoInteractions(notifications);
            messages.delete(msg);
            org.mockito.Mockito.verify(notifications).requestContent(history.getId());
        });
    }

    @Test
    void identicalResultsPreserveIdsTimestampsAndIssueNoBusinessDml() {
        runner().run(context -> {
            assertNull(context.getStartupFailure());
            var writer = context.getBean(StatsResultWriter.class);
            var sessions = context.getBean(RoomLiveSessionStatsRepository.class);
            var buckets = context.getBean(RoomLiveMsgBucketStatsRepository.class);
            var metrics = context.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();
            assertTrue(writer.save(session(11L, "r", 2), List.of(bucket(11L, 0, 2))));
            var original = sessions.findByHistoryId(11L);
            var originalBucket = buckets.findByHistoryIdOrderByBucketIndexAsc(11L).get(0);
            metrics.clear();
            for (int i = 0; i < 4; i++) {
                var candidate = session(11L, "r", 2);
                candidate.setGiftAmountCny(new BigDecimal("0.000"));
                assertFalse(writer.save(candidate, List.of(bucket(11L, 0, 2))));
            }
            assertEquals(0, metrics.getEntityInsertCount());
            assertEquals(0, metrics.getEntityUpdateCount());
            assertEquals(0, metrics.getEntityDeleteCount());
            assertEquals(original.getStatsUpdatedAt(), sessions.findByHistoryId(11L).getStatsUpdatedAt());
            assertEquals(originalBucket.getId(), buckets.findByHistoryIdOrderByBucketIndexAsc(11L).get(0).getId());
            assertEquals(originalBucket.getStatsUpdatedAt(), buckets.findByHistoryIdOrderByBucketIndexAsc(11L).get(0).getStatsUpdatedAt());
        });
    }

    @Test
    void unchangedScanDoesNotWaitForHistoryWriteLock() {
        runner().run(context -> {
            var store = context.getBean(StatsUpdateStore.class);
            seedHistory(context, 11L);
            store.observe(11L, "r", "content", "metadata", true, false);
            var before = store.find(11L);
            try (var connection = context.getBean(javax.sql.DataSource.class).getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM record_history WHERE id=11 FOR UPDATE")) {
                    statement.executeQuery().close();
                }
                try {
                    assertDoesNotThrow(() -> store.observe(11L, "r", "content", "metadata", true, false));
                    assertEquals(before, store.find(11L));
                } finally { connection.rollback(); }
            }
        });
    }

    @Test
    void priceQueueDoesNotLockHistoryAndPreservesRawRevisionAndImportProtection() {
        runner().run(context -> {
            var store = context.getBean(StatsUpdateStore.class);
            var room = new RecordRoom(); room.setRoomId("r");
            context.getBean(RecordRoomRepository.class).save(room);
            seedHistory(context, 11L); seedHistory(context, 12L); seedHistory(context, 13L);
            var imported = session(12L, "r", 1); imported.setImportedSnapshot(true);
            context.getBean(RoomLiveSessionStatsRepository.class).save(imported);
            store.request(13L, "r", StatsUpdateStore.CONTENT, true);
            var state = store.find(13L);
            store.activate(new StatsUpdateStore.Ticket(13L, state.requested(), "bad", "m", state.rawRevision()));
            store.blockActiveXml(); store.deactivate();
            try (var connection = context.getBean(javax.sql.DataSource.class).getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM record_history WHERE id=11 FOR UPDATE")) {
                    statement.executeQuery().close();
                }
                try {
                    assertDoesNotThrow(() -> store.requestPrices(List.of(11L, 11L, 12L, 13L, 9999L)));
                } finally { connection.rollback(); }
            }
            assertEquals(1, store.find(11L).requested());
            assertEquals(4, store.find(11L).reasons());
            assertEquals(0, store.find(11L).rawRevision());
            assertNull(store.find(12L)); assertNull(store.find(9999L));
            assertTrue(store.find(13L).blockedXml());
            assertEquals(5, store.find(13L).reasons());
            assertEquals(state.rawRevision(), store.find(13L).rawRevision());
            new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                store.requestPrices(List.of(11L));
                status.setRollbackOnly();
            });
            assertEquals(1, store.find(11L).requested());
        });
    }

    @Test
    void localizedContentDiffAndMetadataUpdatesKeepUnchangedBuckets() {
        runner().run(context -> {
            var writer = context.getBean(StatsResultWriter.class);
            var buckets = context.getBean(RoomLiveMsgBucketStatsRepository.class);
            var store = context.getBean(StatsUpdateStore.class);
            writer.save(session(11L, "r", 5), List.of(bucket(11L, 0, 2), bucket(11L, 1, 3)));
            var before = buckets.findByHistoryIdOrderByBucketIndexAsc(11L);
            writer.save(session(11L, "r", 6), List.of(bucket(11L, 0, 2), bucket(11L, 1, 4)));
            var after = buckets.findByHistoryIdOrderByBucketIndexAsc(11L);
            assertEquals(before.get(0).getId(), after.get(0).getId());
            assertEquals(before.get(0).getStatsUpdatedAt(), after.get(0).getStatsUpdatedAt());
            assertEquals(before.get(1).getId(), after.get(1).getId());
            var candidate = session(11L, "r", 6);
            candidate.setPublished(true);
            writer.metadata(candidate, false);
            assertEquals(after, buckets.findByHistoryIdOrderByBucketIndexAsc(11L));
            assertEquals(1, store.dailyDue().size());
            store.completeDaily(store.dailyDue().get(0));
            candidate.setLiveDate(LocalDate.of(2026, 10, 5));
            writer.metadata(candidate, false);
            assertEquals(2, store.dailyDue().size());
            writer.save(candidate, List.of(bucket(11L, 0, 2)));
            assertEquals(1, buckets.findByHistoryIdOrderByBucketIndexAsc(11L).size());
        });
    }

    @Test
    void durableRevisionCannotSwallowUpdatesAndRollbackRetainsTasks() {
        runner().run(context -> {
            var store = context.getBean(StatsUpdateStore.class);
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            seedHistory(context, 11L);
            seedHistory(context, 12L);
            store.request(11L, "r", StatsUpdateStore.CONTENT, true);
            var old = store.find(11L);
            store.request(11L, "r", StatsUpdateStore.METADATA, false);
            store.complete(new StatsUpdateStore.Ticket(11L, old.requested(), "content", "metadata", old.rawRevision()));
            assertEquals(1, store.due(200).size());
            assertEquals(2, store.find(11L).requested());
            assertEquals(1, store.find(11L).applied());
            assertEquals(1, store.find(11L).rawRevision());
            tx.executeWithoutResult(status -> {
                store.request(12L, "r", StatsUpdateStore.CONTENT, true);
                status.setRollbackOnly();
            });
            assertNull(store.find(12L));
            var reloaded = new StatsUpdateStore(context.getBean(JdbcTemplate.class), context.getBean(PlatformTransactionManager.class));
            assertEquals(1, reloaded.due(200).size());
            store.retry(store.find(11L), "busy");
            assertTrue(store.due(200).isEmpty());
            store.request(11L, "r", StatsUpdateStore.METADATA, false);
            assertEquals(1, store.due(200).size());
        });
    }

    @Test
    void baselineCleanupSuppressionAndDailyCompletionAreIdempotent() {
        runner().run(context -> {
            var store = context.getBean(StatsUpdateStore.class);
            seedHistory(context, 11L);
            store.observe(11L, "r", "c1", "m1", true, false);
            assertTrue(store.due(200).isEmpty());
            store.observe(11L, "r", "c1", "m1", true, false);
            assertEquals(0, store.find(11L).requested());
            store.suppressAll();
            store.observe(11L, "r", "c1", "m1", false, false);
            assertTrue(store.due(200).isEmpty());
            store.observe(11L, "r", "c2", "m1", false, false);
            assertFalse(store.find(11L).suppressed());
            assertEquals(1, store.due(200).size());
            store.daily("r", LocalDate.of(2026,10,4));
            var d = store.dailyDue().get(0);
            assertThrows(IllegalStateException.class, () -> store.dailyTransaction(d, () -> { throw new IllegalStateException("crash"); }));
            assertEquals(1, store.dailyDue().size());
            store.daily("r", d.date());
            store.completeDaily(d);
            assertEquals(1, store.dailyDue().size());
            store.dailyTransaction(store.dailyDue().get(0), () -> {});
            assertTrue(store.dailyDue().isEmpty());
        });
    }

    private void seedHistory(org.springframework.context.ApplicationContext context, Long id) {
        var saved = context.getBean(RecordHistoryRepository.class).save(StatsUpdateServiceTest.history(null));
        context.getBean(JdbcTemplate.class).update("UPDATE record_history SET id=? WHERE id=?", id, saved.getId());
    }

    @Test
    void cachedXmlFailureStillAllowsMetadataAndWaitsForActualContentChange() {
        runner().run(context -> {
            var store = context.getBean(StatsUpdateStore.class);
            seedHistory(context, 11L);
            store.observe(11L, "r", "good", "m1", true, false);
            store.observe(11L, "r", "bad", "m1", true, false);
            var state = store.find(11L);
            store.activate(new StatsUpdateStore.Ticket(11L, state.requested(), "bad", "m1", state.rawRevision()));
            store.blockActiveXml(); store.deactivate();
            assertTrue(store.due(200).isEmpty());
            store.request(11L, "r", StatsUpdateStore.METADATA, false);
            assertEquals(1, store.due(200).size());
            state = store.find(11L);
            assertTrue(state.blockedXml());
            store.activate(new StatsUpdateStore.Ticket(11L, state.requested(), "bad", "m2", state.rawRevision()));
            store.completeMetadataActive(); store.deactivate();
            assertTrue(store.due(200).isEmpty());
            assertEquals("good", store.find(11L).appliedContent());
            assertEquals("m2", store.find(11L).appliedMetadata());
            store.observe(11L, "r", "repaired", "m2", true, false);
            assertEquals(1, store.due(200).size());
            assertFalse(store.find(11L).blockedXml());
        });
    }

    private RoomLiveSessionStats session(Long id, String room, long count) {
        var s = new RoomLiveSessionStats();
        s.setHistoryId(id); s.setRoomId(room); s.setLiveDate(LocalDate.of(2026,10,4));
        s.setMsgCount(count); s.setStatsUpdatedAt(LocalDateTime.now()); s.setStatsVersion(3);
        return s;
    }

    @Test
    void resultDailyEnqueueAndCompletionShareTheSameTransaction() {
        runner().run(context -> {
            var store = context.getBean(StatsUpdateStore.class);
            var writer = context.getBean(StatsResultWriter.class);
            var histories = context.getBean(RecordHistoryRepository.class);
            var history = histories.save(StatsUpdateServiceTest.history(null));
            store.request(history.getId(), "r", StatsUpdateStore.CONTENT, true);
            var state = store.find(history.getId());
            store.activate(new StatsUpdateStore.Ticket(history.getId(), state.requested(), "c", "m", state.rawRevision()));
            try {
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(status -> {
                    writer.save(session(history.getId(), "r", 2), List.of(bucket(history.getId(), 0, 2)));
                    status.setRollbackOnly();
                });
                assertNull(context.getBean(RoomLiveSessionStatsRepository.class).findByHistoryId(history.getId()));
                assertEquals(0, store.find(history.getId()).applied());
                assertTrue(store.dailyDue().isEmpty());
                writer.save(session(history.getId(), "r", 2), List.of(bucket(history.getId(), 0, 2)));
                assertEquals(state.requested(), store.find(history.getId()).applied());
                assertEquals(1, store.dailyDue().size());
            } finally { store.deactivate(); }
        });
    }
    private RoomLiveMsgBucketStats bucket(Long historyId, int index, long count) {
        var b = new RoomLiveMsgBucketStats();
        b.setHistoryId(historyId); b.setRoomId("r"); b.setBucketIndex(index); b.setBucketStartMs(index * 60000L);
        b.setMsgCount(count); b.setStatsUpdatedAt(LocalDateTime.now()); b.setStatsVersion(3);
        return b;
    }
}
