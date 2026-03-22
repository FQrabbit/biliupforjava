package top.sshh.bililiverecoder.config.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.util.LogKvs;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Component
@Order(20)
public class DatabaseIndexCleanupRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public DatabaseIndexCleanupRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url == null || !url.contains(":h2:")) {
                return;
            }

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.INDEXES " +
                            "WHERE UPPER(INDEX_NAME) = 'IDX_ROOM_ID' " +
                            "AND UPPER(TABLE_NAME) IN ('RECORD_HISTORY', 'RECORD_HISTORY_PART')",
                    Integer.class
            );
            if (count == null || count <= 0) {
                return;
            }

            log.info("[BLR] {}", LogKvs.event("Database.Index.Cleanup.Start")
                    .add("index", "IDX_ROOM_ID")
                    .add("count", count)
                    .add("url", url));
            jdbcTemplate.execute("DROP INDEX IDX_ROOM_ID");
            log.info("[BLR] {}", LogKvs.event("Database.Index.Cleanup.Success").add("index", "IDX_ROOM_ID"));
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Database.Index.Cleanup.Failed")
                    .add("index", "IDX_ROOM_ID")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }
}