package top.sshh.bililiverecoder.service.backup;

import com.alibaba.fastjson.JSON;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.io.IOException;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 记录和映射放在临时库中，大备份不会把全部弹幕留在内存 */
public final class BackupStage implements AutoCloseable {
    public record Row(String section, String key, Map<String, Object> data, Long targetId, boolean conflict, String reason) {}
    public final String id;
    public final Path directory;
    public final JdbcTemplate jdbc;
    private final Connection connection;
    public final AtomicBoolean cancelled = new AtomicBoolean();
    public final Map<String, Long> counts = Collections.synchronizedMap(new LinkedHashMap<>());
    public volatile String phase = "UPLOADING";
    public volatile String message = "正在上传备份";
    public volatile long processed;
    public volatile long bytes;
    public volatile long expectedBytes;
    public volatile long lastTouched = System.currentTimeMillis();
    public volatile String fingerprint;
    public volatile Map<String, Object> result;
    public int nextChunk;
    public long expandedBytes;
    public final long started = System.currentTimeMillis();

    public BackupStage(Path root) throws SQLException, IOException {
        id = UUID.randomUUID().toString();
        directory = Files.createDirectory(root.resolve(id));
        Files.writeString(directory.resolve("owner.pid"),Long.toString(ProcessHandle.current().pid()));
        connection = DriverManager.getConnection("jdbc:h2:file:" + directory.resolve("stage").toAbsolutePath().toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE", "sa", "");
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("CREATE TABLE staged(section VARCHAR(80), source_key VARCHAR(255), payload CLOB, target_id BIGINT, conflict BOOLEAN DEFAULT FALSE, reason VARCHAR(255), PRIMARY KEY(section,source_key))");
        jdbc.execute("CREATE TABLE mappings(section VARCHAR(80), source_key VARCHAR(255), target_key VARCHAR(255), disposition VARCHAR(16), PRIMARY KEY(section,source_key))");
        jdbc.execute("CREATE TABLE boundary_links(history_id BIGINT PRIMARY KEY, backup_key VARCHAR(36))");
        jdbc.execute("CREATE TABLE dirty_dates(room_id VARCHAR(255), live_date VARCHAR(32), PRIMARY KEY(room_id,live_date))");
    }
    public void check() {
        lastTouched = System.currentTimeMillis();
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new IllegalStateException("备份任务已取消");
    }
    public void add(String section, Map<String, Object> data) {
        check();
        BackupSchema.Section definition = BackupSchema.SECTIONS.get(section);
        if (definition == null) return;
        if(data==null)throw new IllegalArgumentException("备份记录不能为空");
        Object value = data.get(definition.primary());
        String key = value == null ? "row-" + counts.getOrDefault(section, 0L) : value.toString();
        jdbc.update("INSERT INTO staged(section,source_key,payload) VALUES(?,?,?)", section, key, JSON.toJSONString(data));
        counts.merge(section, 1L, Long::sum);
        processed++;
    }
    public void rows(String section, java.util.function.Consumer<Row> action) {
        String[] cursor={""};
        while(true) {
            check();int[] scanned={0};
            jdbc.query("SELECT * FROM staged WHERE section=? AND source_key>? ORDER BY source_key LIMIT 16",(org.springframework.jdbc.core.RowCallbackHandler) r->{
                check();Row row=new Row(section,r.getString("source_key"),JSON.parseObject(r.getString("payload"),Map.class,com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect),r.getObject("target_id",Long.class),r.getBoolean("conflict"),r.getString("reason"));
                action.accept(row);cursor[0]=row.key();scanned[0]++;
            },section,cursor[0]);
            if(scanned[0]==0)return;
        }
    }
    public Row find(String section, Object key) {
        if (key == null) return null;
        List<Row> values=jdbc.query("SELECT * FROM staged WHERE section=? AND source_key=?", (r,n) -> new Row(section,r.getString("source_key"),JSON.parseObject(r.getString("payload"),Map.class,com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect),r.getObject("target_id",Long.class),r.getBoolean("conflict"),r.getString("reason")),section,key.toString());
        return values.isEmpty()?null:values.get(0);
    }
    public void map(String section, String key, Object target, String disposition) {
        jdbc.update("MERGE INTO mappings(section,source_key,target_key,disposition) KEY(section,source_key) VALUES(?,?,?,?)", section,key,target==null?null:target.toString(),disposition);
    }
    public String target(String section, Object key) {
        if (key==null) return null;
        List<String> result=jdbc.queryForList("SELECT target_key FROM mappings WHERE section=? AND source_key=?",String.class,section,key.toString());
        return result.isEmpty()?null:result.get(0);
    }
    public String disposition(String section, Object key) {
        if (key==null) return null;
        List<String> result=jdbc.queryForList("SELECT disposition FROM mappings WHERE section=? AND source_key=?",String.class,section,key.toString());
        return result.isEmpty()?null:result.get(0);
    }
    public Map<String,Object> status() {
        Map<String,Object> status=new LinkedHashMap<>();
        status.put("taskId",id); status.put("phase",phase); status.put("message",message);
        status.put("processed",processed); synchronized(counts) {status.put("counts",new LinkedHashMap<>(counts));}
        status.put("recordsTotal",Set.of("PREVIEW","APPLYING","DONE").contains(phase)?counts.values().stream().mapToLong(Long::longValue).sum():0); status.put("bytesProcessed",bytes);
        status.put("bytesTotal",expectedBytes); status.put("elapsedMillis",System.currentTimeMillis()-started);
        status.put("running",Set.of("UPLOADING","PARSING","PREVIEWING","APPLYING").contains(phase));
        status.put("result",result);
        return status;
    }
    @Override public void close() throws SQLException, IOException {
        connection.close();
        try(var files=Files.walk(directory)) {
            for(Path path:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
