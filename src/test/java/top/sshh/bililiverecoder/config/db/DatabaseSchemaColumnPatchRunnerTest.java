package top.sshh.bililiverecoder.config.db;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseSchemaColumnPatchRunnerTest {

    @Test
    void addsImportedSnapshotWithFalseDefaultForExistingRows() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-column-patch;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE TABLE room_live_session_stats (id BIGINT PRIMARY KEY)");
        jdbcTemplate.update("INSERT INTO room_live_session_stats (id) VALUES (1)");

        DatabaseSchemaColumnPatchRunner runner = new DatabaseSchemaColumnPatchRunner(dataSource, jdbcTemplate);
        runner.run(new DefaultApplicationArguments(new String[0]));
        runner.run(new DefaultApplicationArguments(new String[0]));

        Boolean importedSnapshot = jdbcTemplate.queryForObject(
                "SELECT imported_snapshot FROM room_live_session_stats WHERE id = 1",
                Boolean.class
        );
        Integer columnCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME) = 'ROOM_LIVE_SESSION_STATS' " +
                        "AND UPPER(COLUMN_NAME) = 'IMPORTED_SNAPSHOT'",
                Integer.class
        );

        assertThat(importedSnapshot).isFalse();
        assertThat(columnCount).isEqualTo(1);
    }
}
