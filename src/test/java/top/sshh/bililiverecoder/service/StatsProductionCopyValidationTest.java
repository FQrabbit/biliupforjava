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
import org.springframework.core.task.TaskExecutor;
import top.sshh.bililiverecoder.config.db.*;
import top.sshh.bililiverecoder.repo.*;
import javax.sql.DataSource;
import jakarta.persistence.EntityManagerFactory;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StatsProductionCopyValidationTest {
    @Test
    void replayAggregationOnlyOnExplicitlyProvidedIsolatedCopy() throws Exception {
        String configured = System.getProperty("stats.validation.database");
        assumeTrue(configured != null);
        Path file = Path.of(configured + ".mv.db").toAbsolutePath().normalize();
        assertTrue(file.toString().contains(java.io.File.separator + ".ai" + java.io.File.separator));
        assertTrue(Files.isRegularFile(file));
        long originalSize = Files.size(file);
        long started = System.nanoTime();
        var rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var previousLevel = rootLogger.getLevel();
        rootLogger.setLevel(ch.qos.logback.classic.Level.WARN);
        try {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withUserConfiguration(IsolatedStatsConfiguration.class)
                .withInitializer(context -> registerInitializedMocks(context.getBeanFactory()))
                .withPropertyValues("spring.datasource.url=jdbc:h2:file:" + configured.replace('\\','/'),
                        "spring.datasource.username=sa", "spring.datasource.password=" + System.getenv("BILIUP_PROBE_PASSWORD"), "spring.jpa.hibernate.ddl-auto=update",
                        "spring.jpa.properties.hibernate.generate_statistics=true")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var aggregation = context.getBean(StatsAggregationService.class);
                    var store = context.getBean(StatsUpdateStore.class);
                    var jdbc = context.getBean(JdbcTemplate.class);
                    var rooms = context.getBean(RecordRoomRepository.class);
                    var metrics = context.getBean(EntityManagerFactory.class).unwrap(SessionFactory.class).getStatistics();
                    long bucketsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM room_live_msg_bucket_stats", Long.class);
                    var first = aggregation.refreshRecentCompletedHistories(200);
                    for (var daily : store.dailyDue()) store.dailyTransaction(daily, () -> aggregation.recomputeDailyStats(
                            daily.roomId(), daily.date(), rooms.findByRoomId(daily.roomId()), LocalDateTime.now()));
                    var ids = jdbc.queryForList("SELECT id,history_id,bucket_index,stats_updated_at FROM room_live_msg_bucket_stats ORDER BY id");
                    metrics.clear();
                    for (int cycle=0;cycle<3;cycle++) {
                        var result = aggregation.refreshRecentCompletedHistories(200);
                        assertEquals(0, result.get("updated"));
                        assertTrue(store.dailyDue().isEmpty());
                    }
                    assertEquals(0, metrics.getEntityInsertCount()); assertEquals(0, metrics.getEntityUpdateCount());
                    assertEquals(0, metrics.getEntityDeleteCount());
                    assertEquals(bucketsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM room_live_msg_bucket_stats", Long.class));
                    assertEquals(ids, jdbc.queryForList("SELECT id,history_id,bucket_index,stats_updated_at FROM room_live_msg_bucket_stats ORDER BY id"));
                    String result = "firstPassUpdated=" + first.get("updated") + "\nreplayCycles=3\nreplayHistoriesPerCycle=200"
                            + "\nbusinessEntityInserts=0\nbusinessEntityUpdates=0\nbusinessEntityDeletes=0\nbucketRows=" + bucketsBefore
                            + "\nbucketIdsAndTimestampsUnchanged=true\nsourceXmlParsing=mocked-use-existing-parsed-data\nexternalApis=disabled\n";
                    Files.writeString(file.resolveSibling("stats-copy-validation.txt"), result);
                });
        } finally { rootLogger.setLevel(previousLevel); }
        Files.writeString(file.resolveSibling("stats-copy-validation.txt"), "elapsedSeconds=" + (System.nanoTime()-started)/1_000_000_000L
                + "\nfileBytesBefore=" + originalSize + "\nfileBytesAfter=" + Files.size(file) + "\n", StandardOpenOption.APPEND);
    }

    private static void registerInitializedMocks(org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory) {
        var parser = mock(RoomLiveEventParseService.class);
        var skipped = new RoomLiveEventParseService.ParseResult(false,0,"existing parsed data",null);
        when(parser.parsePart(any(), anyBoolean())).thenReturn(skipped);
        when(parser.parsePartQuietly(any(), anyBoolean())).thenReturn(skipped);
        var gifts = mock(RoomLiveGiftCatalogService.class);
        when(gifts.toCny(anyLong())).thenAnswer(i -> BigDecimal.valueOf((Long)i.getArgument(0)).divide(BigDecimal.valueOf(1000)));
        factory.registerSingleton("roomLiveEventParseService", parser);
        factory.registerSingleton("giftCatalogService", gifts);
        factory.registerSingleton("statsUpdateService", mock(StatsUpdateService.class));
        factory.registerSingleton("xmlIssueService", mock(RoomLiveEventXmlIssueService.class));
        factory.registerSingleton("xmlRepairService", mock(RoomLiveEventXmlRepairService.class));
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EntityScan("top.sshh.bililiverecoder.entity")
    @EnableJpaRepositories("top.sshh.bililiverecoder.repo")
    @Import({StatsAggregationService.class, StatsResultWriter.class, StatsUpdateStore.class, DatabaseMaintenanceState.class})
    static class IsolatedStatsConfiguration {
        @Bean static DatabaseMigrationJpaDependencyPostProcessor migrationDependency() { return new DatabaseMigrationJpaDependencyPostProcessor(); }
        @Bean DatabaseMigrationInitializer databaseMigrationInitializer(DataSource dataSource, JdbcTemplate jdbc) throws Exception {
            return new DatabaseMigrationInitializer(dataSource, jdbc, Files.createTempDirectory("stats-copy-migration-").toString());
        }
        @Bean(name="myAsyncPool") TaskExecutor executor() { return Runnable::run; }
    }
}
