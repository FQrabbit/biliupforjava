package top.sshh.bililiverecoder.config.db;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;
import top.sshh.bililiverecoder.service.WebhookEventDispatcher;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MaintenanceJpaIntegrationTest {
    @TempDir Path directory;

    @Test
    void existingJpaFactoryReadsAndWritesAfterThePoolAndDatabaseAreReplaced() {
        Path work = directory.resolve("work");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, HibernateJpaAutoConfiguration.class))
                .withUserConfiguration(MaintenanceDataSourceConfiguration.class, DatabaseMaintenanceState.class, JpaConfiguration.class)
                .withPropertyValues("spring.datasource.hikari.jdbc-url=jdbc:h2:" + work.resolve("db").toString().replace('\\', '/'),
                        "spring.datasource.username=sa", "spring.datasource.password=123456", "spring.jpa.hibernate.ddl-auto=create")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    EntityManagerFactory factory = context.getBean(EntityManagerFactory.class);
                    var manager = factory.createEntityManager();
                    manager.getTransaction().begin();
                    Probe probe = new Probe();
                    probe.id = 1L;
                    probe.name = "压缩前的数据";
                    manager.persist(probe);
                    manager.getTransaction().commit();
                    manager.close();

                    var dispatcher = mock(WebhookEventDispatcher.class);
                    when(dispatcher.isIdle()).thenReturn(true);
                    var replayContext = mock(org.springframework.context.ApplicationContext.class);
                    when(replayContext.getBean(RecordWebhookInboxService.class)).thenReturn(mock(RecordWebhookInboxService.class));
                    var service = new DatabaseMaintenanceService(context.getBean(MaintenanceDataSource.class),
                            context.getBean(JdbcTemplate.class), dispatcher, replayContext,
                            context.getBean(DatabaseMaintenanceState.class), Runnable::run);
                    ReflectionTestUtils.setField(service, "workPath", work.toString());
                    service.compactAsync();
                    assertEquals("DONE", service.status().get("phase"));

                    manager = factory.createEntityManager();
                    manager.getTransaction().begin();
                    Probe restored = manager.find(Probe.class, 1L);
                    assertEquals("压缩前的数据", restored.name);
                    restored.name = "压缩后继续更新";
                    manager.getTransaction().commit();
                    manager.close();
                    manager = factory.createEntityManager();
                    assertEquals("压缩后继续更新", manager.find(Probe.class, 1L).name);
                    manager.close();
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EntityScan(basePackageClasses = Probe.class)
    static class JpaConfiguration { }

    @Entity(name = "MaintenanceJpaProbe")
    static class Probe {
        @Id Long id;
        String name;
    }
}
