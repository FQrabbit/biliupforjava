package top.sshh.bililiverecoder.config.db;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

class MaintenanceDataSourceConfigurationTest {
    @Test
    void bootSelectsGuardedSourceAndRetainsHikariAndCredentialProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class))
                .withUserConfiguration(MaintenanceDataSourceConfiguration.class, DatabaseMaintenanceState.class)
                .withPropertyValues("spring.datasource.hikari.jdbc-url=jdbc:h2:mem:maintenance-config",
                        "spring.datasource.username=custom_user", "spring.datasource.password=custom_password",
                        "spring.datasource.hikari.maximum-pool-size=3")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertInstanceOf(MaintenanceDataSource.class, context.getBean(DataSource.class));
                    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                    assertSame(context.getBean(DataSource.class), jdbc.getDataSource());
                    assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
                    assertEquals("CUSTOM_USER", jdbc.queryForObject("SELECT CURRENT_USER", String.class));
                    var pool = context.getBean("maintenanceConnectionPool", com.zaxxer.hikari.HikariDataSource.class);
                    assertEquals(3, pool.getMaximumPoolSize());
                    assertEquals("jdbc:h2:mem:maintenance-config", pool.getJdbcUrl());
                });
    }
}
