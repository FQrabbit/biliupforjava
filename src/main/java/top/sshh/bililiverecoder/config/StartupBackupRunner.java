package top.sshh.bililiverecoder.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import top.sshh.bililiverecoder.util.LogKvs;
import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;

@Slf4j
@Component
public class StartupBackupRunner implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    @Value("${record.work-path:.}")
    private String workPath;

    public StartupBackupRunner(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            if (url != null && url.contains(":h2:")) {
                log.info("[BLR] {}", LogKvs.event("Database.Backup.Start").add("url", url));
                
                // 创建备份目录
                File backupDir = new File(workPath, "backup");
                if (!backupDir.exists()) {
                    backupDir.mkdirs();
                }

                String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                String backupFileName = "biliupforjava-DBbackup-" + timestamp + ".zip";
                // 在SQL中为Windows路径转义反斜杠并转义单引号
                String backupPath = new File(backupDir, backupFileName).getAbsolutePath()
                        .replace("\\", "/")
                        .replace("'", "''");

                // H2 备份命令
                String sql = String.format("BACKUP TO '%s'", backupPath);
                jdbcTemplate.execute(sql);
                log.info("[BLR] {}", LogKvs.event("Database.Backup.Success").add("path", backupPath));

                // 清理旧备份
                cleanupOldBackups(backupDir);
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Database.Backup.Failed")
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private void cleanupOldBackups(File backupDir) {
        File[] files = backupDir.listFiles((dir, name) -> name.startsWith("biliupforjava-DBbackup-") && name.endsWith(".zip"));
        if (files != null && files.length > 10) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            // 删除最旧的文件，保留最新的10个
            int filesToDelete = files.length - 10;
            for (int i = 0; i < filesToDelete; i++) {
                if (files[i].delete()) {
                    log.info("[BLR] {}", LogKvs.event("Database.Backup.Cleanup.Success").add("fileName", files[i].getName()));
                } else {
                    log.warn("[BLR] {}", LogKvs.event("Database.Backup.Cleanup.Failed").add("fileName", files[i].getName()));
                }
            }
        }
    }
}
