package top.sshh.bililiverecoder.config.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DataSourceProperties.class)
public class MaintenanceDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource maintenanceConnectionPool(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "dataSource", destroyMethod = "close")
    @Primary
    public MaintenanceDataSource dataSource(@Qualifier("maintenanceConnectionPool") HikariDataSource pool,
                                          DatabaseMaintenanceState state) {
        return new MaintenanceDataSource(pool, state);
    }
}
