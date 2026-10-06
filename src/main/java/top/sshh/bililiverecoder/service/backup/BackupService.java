package top.sshh.bililiverecoder.service.backup;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.sshh.bililiverecoder.entity.ExportConfigParams;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;
import top.sshh.bililiverecoder.util.TaskUtil;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** 备份先校验再写入，旧状态只留作历史事实 */
@Service
public class BackupService {
    public record Conflict(String section, String key, Long targetId, String title, String reason, boolean replaceAllowed) {}
    public record CommitRequest(Map<String,String> decisions) {}
    public record ExportArtifact(Path path, long bytes, String sha256) {}
    private final JdbcTemplate jdbc;
    private final DatabaseMaintenanceState maintenance;
    private final TransactionTemplate transaction;
    private final Path root;
    private final Path backups;
    private final long inputLimit;
    private final long expandedLimit;
    private final ConcurrentHashMap<String,BackupStage> sessions=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Map<String,Object>> completed=new ConcurrentHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"backup-transfer");t.setDaemon(true);return t; });

    public BackupService(JdbcTemplate jdbc, DatabaseMaintenanceState maintenance, PlatformTransactionManager manager,
            @Value("${record.work-path:.}") String workPath,
            @Value("${record.backup.max-input-bytes:8589934592}") long inputLimit,
            @Value("${record.backup.max-expanded-bytes:68719476736}") long expandedLimit) throws IOException {
        this.jdbc=jdbc;this.maintenance=maintenance;this.transaction=new TransactionTemplate(manager);
        this.root=Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"),"biliupforjava-backup"));
        this.backups=Path.of(workPath,"backup").toAbsolutePath();this.inputLimit=inputLimit;this.expandedLimit=expandedLimit;
        cleanupOrphans(System.currentTimeMillis()-86400000L);
    }
    public synchronized Map<String,Object> create(long size) throws Exception {
        if(size<=0 || size>inputLimit) throw new IllegalArgumentException("备份文件大小超过上限或为空");
        if(sessions.size()>=3) throw new IllegalStateException("请先完成或取消已有的导入任务");
        BackupStage stage=new BackupStage(root);stage.expectedBytes=size;sessions.put(stage.id,stage);
        return Map.of("taskId",stage.id,"chunkSize",16*1024*1024,"maxInputBytes",inputLimit);
    }
    public Map<String,Object> quarantined(int page) {
        return Map.of("total",count("backup_quarantine_record","1=1"),"records",jdbc.query("SELECT "+"id,batch_id,section,source_key,reason,created_at FROM backup_quarantine_record ORDER BY id DESC LIMIT 50 OFFSET ?",(r,n)->{Map<String,Object> data=new LinkedHashMap<>();for(var p:BackupSchema.SECTIONS.get("quarantineList").properties())if(!p.name().equals("payload"))data.put(p.name(),BackupSchema.read(r,p));return data;},Math.max(0,page)*50));
    }
    public String quarantinePayload(long id) {
        var payload=jdbc.queryForList("SELECT payload FROM backup_quarantine_record WHERE id=?",String.class,id);
        if(payload.isEmpty())throw new IllegalArgumentException("隔离记录不存在");return payload.get(0);
    }
    public BackupStage session(String id) {
        BackupStage s=sessions.get(id);if(s==null) throw new IllegalArgumentException("导入会话不存在或已过期");return s;
    }
    public void chunk(String id,int index,InputStream input) throws Exception {
        BackupStage s=session(id);
        synchronized(s) {
            s.check();if(!s.phase.equals("UPLOADING") || index!=s.nextChunk) throw new IllegalArgumentException("上传分块顺序不正确，请重新选择备份");
            Path block=s.directory.resolve("chunk.pending");long count=0;
            try(OutputStream out=Files.newOutputStream(block)) {
                byte[] bytes=new byte[65536];int n;
                while((n=input.read(bytes))!=-1) { s.check();count+=n;if(count>16L*1024*1024 || s.bytes+count>s.expectedBytes) throw new IllegalArgumentException("上传分块超过允许范围");out.write(bytes,0,n); }
            }
            if(count==0) throw new IllegalArgumentException("上传分块为空");
            try(OutputStream out=Files.newOutputStream(s.directory.resolve("input"),StandardOpenOption.CREATE,StandardOpenOption.APPEND)) { Files.copy(block,out); }
            Files.delete(block);s.bytes+=count;s.nextChunk++;
        }
    }
    public void prepare(String id) {
        BackupStage s=session(id);
        synchronized(s) {
            if(!s.phase.equals("UPLOADING") || s.bytes!=s.expectedBytes) throw new IllegalArgumentException("备份还没有完整上传");
            s.phase="PARSING";s.message="正在解析并校验备份";
            worker.submit(() -> {
                try { parse(s);s.phase="PREVIEWING";s.message="正在检查目标数据与关联";previewTargets(s);s.fingerprint=fingerprint();s.phase="PREVIEW";s.message="请检查冲突和恢复范围"; }
                catch(Exception e) { fail(s,e); }
            });
        }
    }
    public Map<String,Object> status(String id) {
        Map<String,Object> finished=completed.get(id);return finished!=null?finished:session(id).status();
    }
    public Map<String,Object> preview(String id,int page) {
        BackupStage s=session(id);s.check();if(!s.phase.equals("PREVIEW")) throw new IllegalStateException("备份尚未完成预检");
        List<Conflict> conflicts=s.jdbc.query("SELECT * FROM staged WHERE conflict=TRUE ORDER BY section,source_key LIMIT 100 OFFSET ?",(r,n) -> {
            Map<String,Object> data=JSON.parseObject(r.getString("payload"),Map.class,com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect);String section=r.getString("section");Long target=r.getObject("target_id",Long.class);
            return new Conflict(section,r.getString("source_key"),target,Objects.toString(data.getOrDefault("title",data.getOrDefault("uname",data.getOrDefault("configKey",data.get("name")))),""),r.getString("reason"),(target!=null || section.equals("systemConfigList")) && replacementAllowed(s,section,target));
        },Math.max(0,page)*100);
        return Map.of("taskId",id,"counts",s.counts,"conflicts",conflicts,"conflictCount",s.jdbc.queryForObject("SELECT COUNT(*) FROM staged WHERE conflict=TRUE",Long.class),"page",Math.max(0,page),"missingStats",!s.counts.containsKey("roomLiveSessionStatsList"),"archiveHistories",s.counts.getOrDefault("historyList",0L),"emptyTarget",count("record_history","1=1")==0 && count("record_room","1=1")==0 && count("bili_bili_user","1=1")==0);
    }
    public void commit(String id,CommitRequest request) {
        BackupStage s=sessions.get(id);if(s==null && completed.containsKey(id))return;
        if(s==null)throw new IllegalArgumentException("导入会话不存在");
        synchronized(s) {
            if(s.phase.equals("APPLYING") || s.phase.equals("DONE"))return;
            if(!s.phase.equals("PREVIEW")) throw new IllegalStateException("请先完成导入预检");
            Map<String,String> decisions=request==null || request.decisions()==null?Map.of():Map.copyOf(request.decisions());
            for(String value:decisions.values()) if(!Set.of("KEEP","REPLACE","QUARANTINE").contains(value)) throw new IllegalArgumentException("冲突处理选项无效");
            s.phase="APPLYING";s.message="正在等待后台空闲并恢复数据";
            worker.submit(() -> apply(s,decisions));
        }
    }
    public void cancel(String id) throws Exception {
        if(completed.containsKey(id))return;
        BackupStage s=session(id);
        synchronized(s) {
            s.cancelled.set(true);
            if(Set.of("UPLOADING","PREVIEW","FAILED").contains(s.phase)) { s.phase="CANCELLED";s.message="导入已取消";finishSession(s); }
        }
    }
    private void fail(BackupStage s,Exception e) {
        s.phase=s.cancelled.get()?"CANCELLED":"FAILED";s.message=Objects.toString(e.getMessage(),"备份处理失败");
        try { finishSession(s); }catch(Exception cleanup) { s.message+="；临时数据清理失败"; }
    }
    private void finishSession(BackupStage s) throws Exception {
        Map<String,Object> status=s.status();status.put("finishedAt",System.currentTimeMillis());completed.put(s.id,status);sessions.remove(s.id);s.close();
    }
    @Scheduled(fixedDelay=3600000)
    public void expire() {
        long before=System.currentTimeMillis()-86400000L;
        for(BackupStage s:sessions.values()) if(s.lastTouched<before && Set.of("UPLOADING","PREVIEW").contains(s.phase))try { cancel(s.id); }catch(Exception ignored) {}
        cleanupOrphans(before);
        completed.entrySet().removeIf(e -> !Boolean.TRUE.equals(e.getValue().get("running")) && ((Number)e.getValue().getOrDefault("finishedAt",0)).longValue()<before);
    }
    private void cleanupOrphans(long before) {
        // 只清理本功能创建、超过一天且所属进程已结束的临时目录
        try(var entries=Files.list(root)) {
            for(Path directory:entries.toList()) {
                String name=directory.getFileName().toString();
                if(!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || sessions.containsKey(name))continue;
                if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS) || Files.getLastModifiedTime(directory).toMillis()>=before)continue;
                Path owner=directory.resolve("owner.pid");if(!Files.isRegularFile(owner) || Files.size(owner)>32)continue;
                long pid=Long.parseLong(Files.readString(owner).trim());
                if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))continue;
                if(!directory.toRealPath().startsWith(root.toRealPath()))continue;
                try(var files=Files.walk(directory)) {for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
            }
        }catch(Exception ignored) {}
    }
    private void parse(BackupStage s) throws Exception {
        Path file=s.directory.resolve("input");
        try(InputStream in=Files.newInputStream(file)) {
            int a=in.read(),b=in.read();
            if(a=='P' && b=='K') parseZip(s,file);else parseLegacy(s,file);
        }
    }
    private void parseLegacy(BackupStage s,Path file) throws Exception {
        boolean completed=false;int version=1;Map<String,Long> expected=null;Long total=null;
        try(JSONReader reader=new JSONReader(new BackupRecordReader(new InputStreamReader(Files.newInputStream(file),StandardCharsets.UTF_8)),com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect)) {
            reader.startObject();
            while(reader.hasNext()) {
                String key=reader.readString();
                if(BackupSchema.SECTIONS.containsKey(key)) {
                    s.counts.putIfAbsent(key,0L);reader.startArray();
                    while(reader.hasNext()) s.add(key,reader.readObject(Map.class));reader.endArray();
                } else if(key.equals("configFormatVersion"))version=((Number)reader.readObject()).intValue();
                else if(key.equals("exportCompleted"))completed=Boolean.TRUE.equals(reader.readObject());
                else if(key.equals("recordCount"))total=((Number)reader.readObject()).longValue();
                else if(key.equals("sectionCounts")) { expected=new LinkedHashMap<>();Map<?,?> values=reader.readObject(Map.class);for(var e:values.entrySet())expected.put(e.getKey().toString(),((Number)e.getValue()).longValue()); }
                else skipLegacyValue(reader);
            }
            reader.endObject();
        }
        if(version<1 || version>2 || (version==2 && !completed)) throw new IllegalArgumentException("旧备份版本不支持或导出未完成");
        if(expected!=null && !expected.equals(s.counts))throw new IllegalArgumentException("旧备份数据段数量与实际内容不一致");
        if(total!=null && total!=s.processed)throw new IllegalArgumentException("旧备份总记录数与实际内容不一致");
    }
    private static void skipLegacyValue(JSONReader reader) {
        if(reader.peek()==com.alibaba.fastjson.parser.JSONToken.LBRACE) {
            reader.startObject();while(reader.hasNext()){reader.readString();skipLegacyValue(reader);}reader.endObject();
        } else if(reader.peek()==com.alibaba.fastjson.parser.JSONToken.LBRACKET) {
            reader.startArray();while(reader.hasNext())skipLegacyValue(reader);reader.endArray();
        } else reader.readObject();
    }
    private void parseZip(BackupStage s,Path file) throws Exception {
        try(ZipFile zip=new ZipFile(file.toFile(),StandardCharsets.UTF_8)) {
            Set<String> names=new HashSet<>();var entries=zip.entries();
            while(entries.hasMoreElements()) {
                var entry=entries.nextElement();String name=entry.getName();
                if(!names.add(name) || !(name.equals("manifest.json") || BackupSchema.SECTIONS.keySet().stream().anyMatch(k -> name.equals(k+".jsonl"))))throw new IllegalArgumentException("备份包含重复或未知的数据段");
            }
            var manifestEntry=zip.getEntry("manifest.json");if(manifestEntry==null)throw new IllegalArgumentException("备份缺少清单");
            Map<String,Object> manifest;
            try(InputStream in=zip.getInputStream(manifestEntry)) { byte[] bytes=in.readNBytes(1024*1024+1);if(bytes.length>1024*1024)throw new IllegalArgumentException("备份清单过大");manifest=JSON.parseObject(new String(bytes,StandardCharsets.UTF_8),Map.class,com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect); }
            if(!Objects.equals(manifest.get("configFormatVersion"),3) || !Boolean.TRUE.equals(manifest.get("exportCompleted")))throw new IllegalArgumentException("备份格式不支持或未完成");
            Map<String,Object> sections=(Map<String,Object>)manifest.get("sections");if(sections==null)throw new IllegalArgumentException("备份清单缺少数据段");
            if(names.size()!=sections.size()+1)throw new IllegalArgumentException("清单与备份数据段不一致");
            for(var e:sections.entrySet()) {
                String section=e.getKey();if(!BackupSchema.SECTIONS.containsKey(section))throw new IllegalArgumentException("未知的备份数据段");
                ZipEntry entry=zip.getEntry(section+".jsonl");if(entry==null)throw new IllegalArgumentException("备份数据段缺失："+section);
                Map<String,Object> info=(Map<String,Object>)e.getValue();MessageDigest digest=MessageDigest.getInstance("SHA-256");s.counts.put(section,0L);
                try(InputStream raw=zip.getInputStream(entry);InputStream limited=new FilterInputStream(raw) {
                    @Override public int read(byte[] b,int off,int len)throws IOException { int n=super.read(b,off,len);if(n>0 && (s.expandedBytes+=n)>expandedLimit)throw new IOException("备份展开内容超过上限");return n; }
                };DigestInputStream input=new DigestInputStream(limited,digest);BufferedReader reader=new BufferedReader(new BackupRecordReader(new InputStreamReader(input,StandardCharsets.UTF_8),false))) {
                    String line;while((line=readBoundedLine(reader))!=null) { s.check();s.add(section,JSON.parseObject(line,Map.class,com.alibaba.fastjson.parser.Feature.DisableSpecialKeyDetect)); }
                }
                if(s.counts.get(section)!=((Number)info.get("count")).longValue() || !HexFormat.of().formatHex(digest.digest()).equals(info.get("sha256")))throw new IllegalArgumentException("备份数据段数量或校验值不一致："+section);
            }
        }
    }
    private static String readBoundedLine(Reader reader) throws IOException {
        StringBuilder line=new StringBuilder();int value;
        while((value=reader.read())!=-1) {
            if(value=='\n')return line.toString();
            if(line.length()>=16*1024*1024)throw new IOException("单条备份记录过大");
            if(value!='\r')line.append((char)value);
        }
        return line.isEmpty()?null:line.toString();
    }
    private void previewTargets(BackupStage s) {
        for(String section:BackupSchema.SECTIONS.keySet())s.rows(section,row -> {
            List<Map<String,Object>> matches=matches(section,row.data());Long target=matches.size()==1?(section.equals("systemConfigList")?null:longValue(matches.get(0).get(BackupSchema.SECTIONS.get(section).primary()))):null;
            boolean settings=Set.of("userList","roomList","systemConfigList","notificationChannelList","notificationRuleList").contains(section);
            boolean conflict=(settings || section.equals("historyList")) && !matches.isEmpty();
            String reason=matches.size()>1?"多个目标记录匹配，不能自动合并":conflict?"目标已有记录，默认保留":null;
            if(section.equals("partList") && s.find("historyList",row.data().get("historyId"))==null) { conflict=true;reason="找不到父稿件，将隔离保留"; }
            if(section.equals("historyList") && target!=null && !replacementAllowed(s,section,target)) reason="目标已有弹幕或统计，但备份未包含完整数据段，不能整体替换";
            s.jdbc.update("UPDATE staged SET target_id=?,conflict=?,reason=? WHERE section=? AND source_key=?",target,conflict,reason,section,row.key());
        });
    }
    private List<Map<String,Object>> matches(String section,Map<String,Object> data) {
        return switch(section) {
            case "userList" -> query(section,"uid=?",data.get("uid"));
            case "roomList" -> query(section,"room_id=?",data.get("roomId"));
            case "systemConfigList" -> query(section,"config_key=?",data.get("configKey"));
            case "storageRootList" -> query(section,"root_key=?",data.get("rootKey"));
            case "roomLiveDailyStatsList" -> query(section,"room_id=? AND live_date=?",data.get("roomId"),BackupSchema.sql(BackupSchema.SECTIONS.get(section).property("liveDate"),data.get("liveDate")));
            case "roomLiveGiftCatalogList" -> query(section,"room_id=? AND gift_id=?",data.get("roomId"),data.get("giftId"));
            case "notificationChannelList" -> query(section,"type=? AND name=?",data.get("type"),data.get("name"));
            case "notificationRuleList" -> query(section,"event_type=? AND room_id=?",data.get("eventType"),data.getOrDefault("roomId","*"));
            case "historyList" -> {
                List<Map<String,Object>> found=List.of();
                if(nonblank(data.get("backupKey"))) found=query(section,"backup_key=?",data.get("backupKey"));
                if(found.isEmpty() && nonblank(data.get("eventId")))found=query(section,"event_id=?",data.get("eventId"));
                if(found.isEmpty() && !nonblank(data.get("eventId")))found=query(section,"room_id=? AND ((session_id=? ) OR (session_id IS NULL AND ? IS NULL)) AND start_time=? AND ((bv_id=?) OR (bv_id IS NULL AND ? IS NULL))",data.get("roomId"),data.get("sessionId"),data.get("sessionId"),BackupSchema.sql(BackupSchema.SECTIONS.get(section).property("startTime"),data.get("startTime")),data.get("bvId"),data.get("bvId"));
                yield found;
            }
            default -> List.of();
        };
    }
    private List<Map<String,Object>> query(String section,String where,Object... args) {
        BackupSchema.Section def=BackupSchema.SECTIONS.get(section);
        return jdbc.query("SELECT "+def.columns()+" FROM "+def.table()+" WHERE "+where,(r,n)->read(def,r),args);
    }
    private static Map<String,Object> read(BackupSchema.Section def,ResultSet r)throws SQLException {
        Map<String,Object> data=new LinkedHashMap<>();for(var p:def.properties())data.put(p.name(),BackupSchema.read(r,p));return data;
    }
    private boolean replacementAllowed(BackupStage s,String section,Long target) {
        if(!section.equals("historyList"))return true;
        if(target==null)return false;
        if(!s.counts.containsKey("partList"))return false;
        if(!s.counts.containsKey("partFileLocationList") && count("part_file_location","part_id IN (SELECT id FROM record_history_part WHERE history_id=?)",target)>0)return false;
        if(!s.counts.containsKey("liveMsgList") && count("live_msg","part_id IN (SELECT id FROM record_history_part WHERE history_id=?)",target)>0)return false;
        for(String name:BackupSchema.SECTIONS.keySet())if(name.startsWith("roomLive") && !name.equals("roomLiveGiftCatalogList") && !name.equals("roomLiveDailyStatsList")) {
            if(!s.counts.containsKey(name) && count(BackupSchema.SECTIONS.get(name).table(),"history_id=?",target)>0)return false;
        }
        return true;
    }
    private long count(String table,String where,Object... args) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+where,Long.class,args); }
    private String fingerprint() {
        try {
            MessageDigest hash=MessageDigest.getInstance("SHA-256");
            for(var def:BackupSchema.SECTIONS.values())visit(def,row -> hash.update((def.name()+JSON.toJSONString(row)).getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(hash.digest());
        }catch(Exception e){throw new IllegalStateException("无法校验目标库",e);}
    }
    private void visit(BackupSchema.Section def,java.util.function.Consumer<Map<String,Object>> action) {
        Object[] last={null};String primary=def.property(def.primary()).column();
        while(true) {
            if(Thread.currentThread().isInterrupted())throw new IllegalStateException("备份任务已中断");
            int[] scanned={0};
            String sql="SELECT "+def.columns()+" FROM "+def.table()+(last[0]==null?"":" WHERE "+primary+">?")+" ORDER BY "+primary+" LIMIT 16";
            Object[] args=last[0]==null?new Object[0]:new Object[]{last[0]};
            jdbc.query(sql,(org.springframework.jdbc.core.RowCallbackHandler) r->{Map<String,Object> data=read(def,r);action.accept(data);last[0]=data.get(def.primary());scanned[0]++;},args);
            if(scanned[0]==0)return;
        }
    }
    public ExportArtifact export(ExportConfigParams params) throws Exception {
        return export(params,UUID.randomUUID().toString());
    }
    public ExportArtifact export(ExportConfigParams params,String taskId) throws Exception {
        if(!maintenance.tryBeginMaintenance())throw new IllegalStateException("数据库已有维护任务");
        try {
            completed.put(taskId,new LinkedHashMap<>(Map.of("taskId",taskId,"task","export","phase","EXPORTING","message","正在生成完整备份","processed",0L,"running",true,"startedAtEpochMs",System.currentTimeMillis())));
            requireIdle();maintenance.pauseAndDrain(30000);requireIdle();
            ExportArtifact artifact=exportFile(params,Files.createTempFile(root,"export-",".zip"),taskId);
            completed.put(taskId,new LinkedHashMap<>(Map.of("taskId",taskId,"task","export","phase","DONE","message","备份已生成","running",false,"finishedAt",System.currentTimeMillis())));
            return artifact;
        }catch(Exception e) {
            completed.put(taskId,new LinkedHashMap<>(Map.of("taskId",taskId,"task","export","phase","FAILED","message",Objects.toString(e.getMessage(),"导出失败"),"running",false,"finishedAt",System.currentTimeMillis())));throw e;
        }
        finally { maintenance.resumeDatabase();maintenance.setMaintenanceActive(false); }
    }
    private ExportArtifact exportFile(ExportConfigParams params,Path file) throws Exception { return exportFile(params,file,null); }
    private ExportArtifact exportFile(ExportConfigParams params,Path file,String taskId) throws Exception {
        Map<String,Object> sections=new LinkedHashMap<>();
        long total=0;for(var def:BackupSchema.SECTIONS.values())if(selected(params,def.name()))total+=count(def.table(),"1=1");
        final long recordsTotal=total;long[] processed={0};
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(file)),StandardCharsets.UTF_8)) {
            for(var def:BackupSchema.SECTIONS.values())if(selected(params,def.name())) {
                MessageDigest digest=MessageDigest.getInstance("SHA-256");long[] count={0};zip.putNextEntry(new ZipEntry(def.name()+".jsonl"));
                visit(def,data -> {
                    try {
                        if (def.name().equals("userList")) {data.remove("publishCaptchaProbeTaskId");data.remove("publishCaptchaRetryAt");}
                        byte[] bytes=(JSON.toJSONString(data)+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>16*1024*1024)throw new IOException("单条备份记录过大："+def.name());zip.write(bytes);digest.update(bytes);count[0]++;processed[0]++;
                        if(taskId!=null && processed[0]%500==0)completed.put(taskId,new LinkedHashMap<>(Map.of("taskId",taskId,"task","export","phase","EXPORTING","message","正在生成备份","processed",processed[0],"recordsTotal",recordsTotal,"running",true)));
                    }
                    catch(IOException e){throw new UncheckedIOException(e);}
                });zip.closeEntry();sections.put(def.name(),Map.of("count",count[0],"sha256",HexFormat.of().formatHex(digest.digest())));
            }
            Map<String,Object> manifest=new LinkedHashMap<>();manifest.put("configFormatVersion",3);manifest.put("programVersion",new top.sshh.bililiverecoder.service.FrontendVersionService().getVersion());manifest.put("exportedAt",LocalDateTime.now().toString());manifest.put("sections",sections);manifest.put("exportCompleted",true);
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(JSON.toJSONString(manifest).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }catch(Exception e){Files.deleteIfExists(file);throw e;}
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new DigestInputStream(Files.newInputStream(file),digest)){in.transferTo(OutputStream.nullOutputStream());}
        return new ExportArtifact(file,Files.size(file),HexFormat.of().formatHex(digest.digest()));
    }
    private static boolean selected(ExportConfigParams p,String section) {
        boolean history=p.isExportHistory() || p.isExportStats() || p.isExportLiveMsg();
        return switch(section) {
            case "userList" -> p.isExportUser() || history || p.isExportRoom();case "roomList" -> p.isExportRoom();
            case "systemConfigList","notificationChannelList","notificationRuleList" -> p.isExportSystemConfig();
            case "historyList","partList","storageRootList","partFileLocationList","quarantineList" -> history;
            case "liveMsgList" -> p.isExportLiveMsg();default -> p.isExportStats();
        };
    }
    private void requireTransactionalTables() throws SQLException {
        String product=jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        if(product!=null && product.toLowerCase(Locale.ROOT).contains("mysql")) {
            for(var section:BackupSchema.SECTIONS.values()) {
                String engine=jdbc.queryForObject("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?",String.class,section.table());
                if(!"InnoDB".equalsIgnoreCase(engine))throw new IllegalStateException("数据表不支持完整回滚，请先转换为 InnoDB："+section.table());
            }
        }
    }
    private void requireIdle() {
        if(count("live_msg","code=-4 AND EXISTS(SELECT 1 FROM record_history_part p,record_history h WHERE p.id=live_msg.part_id AND h.id=p.history_id AND h.import_archived=FALSE)")>0)
            throw new IllegalStateException("请等待正在发送的弹幕结束");
        if(!TaskUtil.partUploadTask.isEmpty() || !TaskUtil.publishTask.isEmpty())throw new IllegalStateException("请等待正在执行的上传和投稿任务结束");
        for(var item:Map.of("publish_task","state IN ('PREPARING','SUBMITTING','VERIFYING')","video_comment_task","state='SUBMITTING' OR pin_state='SUBMITTING'","video_visibility_restore_task","state IN ('PREPARING','ACTIVE','RESTORING')","part_file_operation","status='RUNNING'","history_deletion_task","state='RUNNING'").entrySet())
            if(count(item.getKey(),item.getValue())>0)throw new IllegalStateException("请等待正在执行的后台任务结束："+item.getKey());
    }
    private void apply(BackupStage s,Map<String,String> decisions) {
        boolean owner=false;boolean committed=false;
        try {
            owner=maintenance.tryBeginMaintenance();if(!owner)throw new IllegalStateException("数据库已有维护任务，请稍后重新导入");
            requireIdle();maintenance.pauseAndDrain(30000);requireIdle();requireTransactionalTables();s.check();
            if(!Objects.equals(s.fingerprint,fingerprint()))throw new IllegalStateException("目标数据在预览后发生变化，请重新上传并检查冲突");
            Files.createDirectories(backups);ExportConfigParams all=new ExportConfigParams();all.setExportRoom(true);all.setExportUser(true);all.setExportSystemConfig(true);all.setExportHistory(true);all.setExportLiveMsg(true);all.setExportStats(true);
            Path backup=backups.resolve("before-import-"+s.id+".zip");exportFile(all,backup);
            Map<String,long[]> accounting=new LinkedHashMap<>();s.processed=0;
            transaction.executeWithoutResult(tx -> write(s,decisions,accounting));committed=true;
            Map<String,Object> report=new LinkedHashMap<>();for(var e:accounting.entrySet())report.put(e.getKey(),Map.of("source",s.counts.getOrDefault(e.getKey(),0L),"written",e.getValue()[0],"skipped",e.getValue()[1],"quarantined",e.getValue()[2]));
            s.result=Map.of("sections",report,"backupPath",backup.toString(),"restartRecommended",true);s.phase="DONE";s.message="数据恢复完成，旧历史已归档，请重启核心加载配置";
        }catch(Exception e) {
            s.phase=s.cancelled.get()?"CANCELLED":"FAILED";s.message=(committed?"数据已提交，后续处理失败：":"未写入目标库：")+Objects.toString(e.getMessage(),"导入失败");
        }finally {
            if(owner){maintenance.resumeDatabase();maintenance.setMaintenanceActive(false);}
            try{finishSession(s);}catch(Exception e){completed.put(s.id,s.status());}
        }
    }
    private void write(BackupStage s,Map<String,String> decisions,Map<String,long[]> report) {
        boolean empty=count("record_history","1=1")==0 && count("record_room","1=1")==0 && count("bili_bili_user","1=1")==0;
        for(String section:BackupSchema.SECTIONS.keySet()) {
            long[] counters=new long[3];report.put(section,counters);
            s.rows(section,row -> {
                s.message="正在恢复 "+section;s.check();String choice=decisions.getOrDefault(section+":"+row.key(),empty && section.equals("systemConfigList")?"REPLACE":"KEEP");
                if(choice.equals("REPLACE") && row.conflict() && (row.targetId()==null && !section.equals("systemConfigList") || !replacementAllowed(s,section,row.targetId())))throw new IllegalArgumentException("这条冲突不能整体替换："+section+":"+row.key());
                if(section.equals("systemConfigList") && "storage.lifecycle.migration.version".equals(row.data().get("configKey"))) {
                    s.map(section,row.key(),null,"QUARANTINE");quarantine(s,row,"原库迁移标记不应用到目标库");counters[2]++;
                }
                else if(row.conflict() && choice.equals("KEEP")) { s.map(section,row.key(),row.targetId(),row.targetId()==null && !section.equals("systemConfigList")?"QUARANTINE":"KEEP");if(row.targetId()==null && !section.equals("systemConfigList")){quarantine(s,row,row.reason());counters[2]++;}else counters[1]++; }
                else if(row.conflict() && choice.equals("QUARANTINE")) { s.map(section,row.key(),null,"QUARANTINE");quarantine(s,row,"用户选择隔离保存");counters[2]++; }
                else {
                    Map<String,Object> data=new LinkedHashMap<>(row.data());
                    String parentDisposition=parentDisposition(s,section,data);
                    if("KEEP".equals(parentDisposition)) {
                        var keptPart=section.equals("partList")?matchingPart(data,s.target("historyList",data.get("historyId"))):null;
                        s.map(section,row.key(),keptPart==null?null:keptPart.get("id"),"KEEP");counters[1]++;
                    }
                    else if("QUARANTINE".equals(parentDisposition)) { s.map(section,row.key(),null,"QUARANTINE");quarantine(s,row,"父记录无法关联或已隔离");counters[2]++; }
                    else if(!remap(s,section,data)) { s.map(section,row.key(),null,"QUARANTINE");quarantine(s,row,"必需关联缺失或父稿件不一致");counters[2]++; }
                    else {
                        if(section.equals("historyList") && row.targetId()!=null) {
                            for(var old:query("roomLiveSessionStatsList","history_id=?",row.targetId()))dirtyDate(s,old);
                            bindLegacyParts(s,row);
                            clearHistory(s,row.targetId());
                        }
                        if ((section.equals("historyList") || section.equals("partList")) && !nonblank(data.get("archiveOriginalState")))
                            data.put("archiveOriginalState",JSON.toJSONString(row.data()));
                        normalize(s,section,data,row.targetId());
                        Object target=save(section,data,row.targetId());s.map(section,row.key(),target,"WRITE");
                        if(row.targetId()!=null && Set.of("storageRootList","roomLiveDailyStatsList","roomLiveGiftCatalogList").contains(section))counters[1]++;else counters[0]++;
                        if(section.equals("roomLiveSessionStatsList"))dirtyDate(s,data);
                    }
                }
                s.processed++;
            });
            if(counters[0]+counters[1]+counters[2]!=s.counts.getOrDefault(section,0L))throw new IllegalStateException("导入数量核对失败："+section);
        }
        s.rows("historyList",row -> {
            if(!"WRITE".equals(s.disposition("historyList",row.key())))return;
            Object parent=s.target("historyList",row.data().get("splitParentId"));Object boundary=s.target("partList",row.data().get("splitBoundaryPartId"));
            jdbc.update("UPDATE record_history SET split_parent_id=?,split_boundary_part_id=? WHERE id=?",parent,boundary,s.target("historyList",row.key()));
        });
        rebuildDaily(s);
        s.jdbc.query("SELECT * FROM boundary_links",(org.springframework.jdbc.core.RowCallbackHandler) r->{
            Long historyId=r.getLong("history_id");
            if(s.jdbc.queryForObject("SELECT COUNT(*) FROM mappings WHERE section='historyList' AND target_key=? AND disposition='WRITE'",Long.class,historyId.toString())>0)return;
            var part=query("partList","backup_key=?",r.getString("backup_key"));
            if(part.size()==1)jdbc.update("UPDATE record_history SET split_boundary_part_id=? WHERE id=? AND split_boundary_part_id IS NULL",part.get(0).get("id"),historyId);
        });
        s.check();
        s.rows("partList", row -> {
            if ("WRITE".equals(s.disposition("partList",row.key())) && count("record_history_part", "id=? AND EXISTS(SELECT 1 FROM record_history h WHERE h.id=record_history_part.history_id)",s.target("partList",row.key()))!=1)
                throw new IllegalStateException("新导入分P的关联校验失败，已回滚导入");
        });
    }
    private String parentDisposition(BackupStage s,String section,Map<String,Object> data) {
        if(section.equals("partList") || data.containsKey("historyId") && !section.equals("roomList")) {
            String result=s.disposition("historyList",data.get("historyId"));if(result!=null && !result.equals("WRITE"))return result;
        }
        if(data.containsKey("partId")) {
            String result=s.disposition("partList",data.get("partId"));if(result!=null && !result.equals("WRITE"))return result;
        }
        return null;
    }
    private void dirtyDate(BackupStage s,Map<String,Object> data) {
        if(data.get("roomId")!=null && data.get("liveDate")!=null)
            s.jdbc.update("MERGE INTO dirty_dates(room_id,live_date) KEY(room_id,live_date) VALUES(?,?)",data.get("roomId"),data.get("liveDate").toString());
    }
    private void rebuildDaily(BackupStage s) {
        Map<String,String> sums=new LinkedHashMap<>();
        sums.put("totalDurationSeconds","duration_seconds");sums.put("totalFileSize","file_size");sums.put("totalMsgCount","msg_count");
        sums.put("totalNormalMsgCount","normal_msg_count");sums.put("totalAdvancedMsgCount","advanced_msg_count");sums.put("totalGiftEventCount","gift_event_count");
        sums.put("totalGiftCount","gift_total_count");sums.put("totalGiftCoin","gift_total_coin");sums.put("totalGiftAmountCny","gift_amount_cny");
        sums.put("totalScCount","sc_count");sums.put("totalScAmount","sc_amount");sums.put("totalGuardCount","guard_count");sums.put("totalActiveUserCount","active_user_count");
        s.jdbc.query("SELECT * FROM dirty_dates", (org.springframework.jdbc.core.RowCallbackHandler) r -> {
            s.check();String room=r.getString("room_id"),date=r.getString("live_date");Map<String,Object> data=new LinkedHashMap<>();data.put("roomId",room);data.put("liveDate",date);
            data.put("liveCount",count("room_live_session_stats","room_id=? AND live_date=?",room,date));
            for(var e:sums.entrySet())data.put(e.getKey(),jdbc.queryForObject("SELECT COALESCE(SUM("+e.getValue()+"),0) FROM room_live_session_stats WHERE room_id=? AND live_date=?",java.math.BigDecimal.class,room,date));
            long total=((Number)data.get("totalDurationSeconds")).longValue(),sessions=((Number)data.get("liveCount")).longValue();data.put("averageDurationSeconds",sessions==0?0:total/sessions);
            data.put("publishedCount",count("room_live_session_stats","room_id=? AND live_date=? AND published=TRUE",room,date));
            data.put("successfulPublishCount",count("room_live_session_stats","room_id=? AND live_date=? AND published=TRUE AND publish_code IN(0,-50)",room,date));
            data.put("statsUpdatedAt",LocalDateTime.now().toString());data.put("statsVersion",jdbc.queryForObject("SELECT COALESCE(MAX(stats_version),0) FROM room_live_session_stats WHERE room_id=? AND live_date=?",Integer.class,room,date));
            var names=jdbc.queryForList("SELECT uname FROM record_room WHERE room_id=?",String.class,room);data.put("uname",names.isEmpty()?null:names.get(0));
            var found=query("roomLiveDailyStatsList","room_id=? AND live_date=?",room,date);
            if(found.isEmpty())save("roomLiveDailyStatsList",data,null);
            else {
                List<BackupSchema.Property> fields=BackupSchema.SECTIONS.get("roomLiveDailyStatsList").properties().stream().filter(p->!p.name().equals("id") && data.containsKey(p.name())).toList();
                jdbc.update("UPDATE room_live_daily_stats SET "+String.join(",",fields.stream().map(p->p.column()+"=?").toList())+" WHERE id=?",values(fields,data,found.get(0).get("id")));
            }
        });
    }
    private boolean remap(BackupStage s,String section,Map<String,Object> data) {
        if(section.equals("roomList")) { data.put("uploadUserId",s.target("userList",data.get("uploadUserId")));data.put("historyId",null);return true; }
        if(section.equals("historyList")) {
            BackupStage.Row user=s.find("userList",data.get("publishUserId"));if(user!=null)data.put("originalPublishUid",user.data().get("uid"));
            data.put("publishUserId",s.target("userList",data.get("publishUserId")));data.put("splitParentId",null);data.put("splitBoundaryPartId",null);return true;
        }
        if(data.containsKey("historyId")) { String key=s.target("historyList",data.get("historyId"));if(key==null)return false;data.put("historyId",key); }
        if(data.containsKey("partId") && !(data.get("partId")==null && Set.of("roomLiveDanmuUserStatsList","roomLiveEventList").contains(section))) {
            String key=s.target("partList",data.get("partId"));if(key==null)return false;data.put("partId",key);
            if(data.containsKey("historyId") && count("record_history_part","id=? AND history_id=?",key,data.get("historyId"))==0)return false;
        }
        if(data.get("storageRootId")!=null) { String key=s.target("storageRootList",data.get("storageRootId"));if(key==null)return false;data.put("storageRootId",key); }
        if(section.equals("notificationRuleList")) {
            List<String> ids=new ArrayList<>();for(String token:Objects.toString(data.get("channelIds"),"").split(",")){String key=s.target("notificationChannelList",token.trim());if(!token.isBlank() && key==null)return false;if(key!=null && !ids.contains(key))ids.add(key);}data.put("channelIds",String.join(",",ids));
        }
        if(section.equals("partFileLocationList")) {
            try { Path relative=Path.of(Objects.toString(data.get("relativePath"),""));if(relative.isAbsolute() || relative.normalize().startsWith("..") || !nonblank(data.get("relativePath")) || "PROCESSING".equals(data.get("state")))return false; }catch(Exception e){return false;}
        }
        return true;
    }
    private void normalize(BackupStage s,String section,Map<String,Object> data,Long existing) {
        if(section.equals("historyList") || section.equals("partList")) {
            if(!nonblank(data.get("archiveOriginalState")))data.put("archiveOriginalState",JSON.toJSONString(data));
            if(!nonblank(data.get("backupKey")))data.put("backupKey",UUID.randomUUID().toString());
            data.put("recording",false);data.put("uploadRetryCount",0);data.put("uploadPaused",false);data.put("uploadPausedAt",null);data.put("uploadPauseReason",null);
            if(section.equals("historyList")) {
                data.put("importArchived",true);data.put("importedAt",LocalDateTime.now().toString());data.put("importBatchId",s.id);
                data.put("streaming",false);data.put("upload",false);data.put("editPartsUploading",false);data.put("deletePending",false);
            }else {data.put("deleteRetryCount",0);data.put("deleteFailType",null);data.put("deleteFailReason",null);}
        }
        if(section.equals("roomList")) { data.put("recording",false);data.put("streaming",false);data.put("sessionId",null);data.put("webhookLastSeenAt",null); }
        if(section.equals("userList")) {
            Map<String,Object> current=existing==null?Map.of():query(section,"id=?",existing).get(0);
            data.put("publishCaptchaProbeTaskId",current.get("publishCaptchaProbeTaskId"));data.put("publishCaptchaRetryAt",current.get("publishCaptchaRetryAt"));
            Object task=data.get("publishCaptchaProbeTaskId");if(task==null || count("publish_task","id=? AND account_id=?",task,existing)==0){data.put("publishCaptchaProbeTaskId",null);data.put("publishCaptchaRetryAt",null);}
            for(String field:List.of("publishCooldownUntil","publishNextAllowedAt"))data.put(field,later(data.get(field),current.get(field)));
            data.put("publishRiskFailures",current.getOrDefault("publishRiskFailures",0));data.put("publishSuccessStreak",current.getOrDefault("publishSuccessStreak",0));
        }
        if(section.equals("storageRootList") && existing==null) {data.put("status","OFFLINE");data.put("writable",false);data.put("activeForNewFiles",false);data.put("lastCheckedAt",null);}
        if(section.equals("roomLiveSessionStatsList"))data.put("importedSnapshot",true);
        if(section.equals("partList") && !data.containsKey("isPost"))data.put("isPost",data.getOrDefault("post",false));
        if(section.equals("liveMsgList") && !data.containsKey("isSend"))data.put("isSend",data.getOrDefault("send",false));
    }
    private Object save(String section,Map<String,Object> data,Long existing) {
        BackupSchema.Section def=BackupSchema.SECTIONS.get(section);
        BackupSchema.fillDefaults(section,data);
        if(section.equals("storageRootList") && existing!=null)return existing;
        if(section.equals("roomLiveDailyStatsList")) {
            List<Map<String,Object>> found=query(section,"room_id=? AND live_date=?",data.get("roomId"),data.get("liveDate"));
            if(!found.isEmpty())return found.get(0).get("id");
        }
        if(section.equals("roomLiveGiftCatalogList")) {
            List<Map<String,Object>> found=query(section,"room_id=? AND gift_id=?",data.get("roomId"),data.get("giftId"));if(!found.isEmpty())return found.get(0).get("id");
        }
        List<BackupSchema.Property> fields=def.properties().stream().filter(p -> !p.name().equals(def.primary()) && data.containsKey(p.name())).toList();
        if(!def.generated() && count(def.table(),def.property(def.primary()).column()+"=?",data.get(def.primary()))>0) {
            jdbc.update("UPDATE "+def.table()+" SET "+String.join(",",fields.stream().map(p->p.column()+"=?").toList())+" WHERE "+def.property(def.primary()).column()+"=?",values(fields,data,data.get(def.primary())));return data.get(def.primary());
        }
        if(existing!=null) {
            jdbc.update("UPDATE "+def.table()+" SET "+String.join(",",fields.stream().map(p->p.column()+"=?").toList())+" WHERE "+def.property(def.primary()).column()+"=?",values(fields,data,existing));return existing;
        }
        List<BackupSchema.Property> inserted=new ArrayList<>(fields);if(!def.generated())inserted.add(0,def.property(def.primary()));
        String sql="INSERT INTO "+def.table()+"("+String.join(",",inserted.stream().map(BackupSchema.Property::column).toList())+") VALUES("+String.join(",",Collections.nCopies(inserted.size(),"?"))+")";
        Object[] values=values(inserted,data);
        if(!def.generated()){jdbc.update(sql,values);return data.get(def.primary());}
        GeneratedKeyHolder keys=new GeneratedKeyHolder();jdbc.update(c->{PreparedStatement statement=c.prepareStatement(sql,new String[]{def.property(def.primary()).column()});for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);return statement;},keys);
        return keys.getKey().longValue();
    }
    private static Object[] values(List<BackupSchema.Property> fields,Map<String,Object> data,Object... extra) {
        List<Object> result=new ArrayList<>();for(var p:fields)result.add(BackupSchema.sql(p,data.get(p.name())));result.addAll(Arrays.asList(extra));return result.toArray();
    }
    private void quarantine(BackupStage s,BackupStage.Row row,String reason) {
        Map<String,Object> data=new LinkedHashMap<>();data.put("batchId",s.id);data.put("section",row.section());data.put("sourceKey",row.key());data.put("reason",reason);data.put("payload",JSON.toJSONString(row.data()));data.put("createdAt",LocalDateTime.now().toString());save("quarantineList",data,null);
    }
    private Map<String,Object> matchingPart(Map<String,Object> source,Object historyId) {
        if(historyId==null)return null;
        List<Map<String,Object>> matches=List.of();
        if(nonblank(source.get("backupKey")))matches=query("partList","history_id=? AND backup_key=?",historyId,source.get("backupKey"));
        else if(nonblank(source.get("eventId")))matches=query("partList","history_id=? AND event_id=?",historyId,source.get("eventId"));
        else if(nonblank(source.get("filePath"))) {
            Object start=BackupSchema.sql(BackupSchema.SECTIONS.get("partList").property("startTime"),source.get("startTime"));
            matches=query("partList","history_id=? AND file_path=? AND (start_time=? OR (start_time IS NULL AND ? IS NULL)) AND (cid=? OR (cid IS NULL AND ? IS NULL))",historyId,source.get("filePath"),start,start,source.get("cid"),source.get("cid"));
        }
        return matches.size()==1?matches.get(0):null;
    }
    private void bindLegacyParts(BackupStage s,BackupStage.Row history) {
        s.rows("partList",row->{
            if(!history.key().equals(Objects.toString(row.data().get("historyId"),"")) || nonblank(row.data().get("backupKey")))return;
            var existing=matchingPart(row.data(),history.targetId());if(existing==null)return;
            var data=new LinkedHashMap<>(row.data());data.putIfAbsent("archiveOriginalState",JSON.toJSONString(row.data()));data.put("backupKey",existing.get("backupKey"));
            s.jdbc.update("UPDATE staged SET payload=? WHERE section='partList' AND source_key=?",JSON.toJSONString(data),row.key());
        });
    }
    private void clearHistory(BackupStage s,Long id) {
        // 其他历史可能引用待替换的分P，先保留原关系，再按稳定标识恢复
        for(Long child:jdbc.queryForList("SELECT id FROM record_history WHERE id<>? AND split_boundary_part_id IN (SELECT id FROM record_history_part WHERE history_id=?)",Long.class,id,id)) {
            var data=query("historyList","id=?",child).get(0);
            var part=query("partList","id=?",data.get("splitBoundaryPartId")).get(0);
            s.jdbc.update("MERGE INTO boundary_links(history_id,backup_key) KEY(history_id) VALUES(?,?)",child,part.get("backupKey"));
            jdbc.update("UPDATE record_history SET archive_original_state=COALESCE(archive_original_state,?) WHERE id=?",JSON.toJSONString(data),child);
        }
        for(String table:List.of("multipart_upload_part","multipart_upload_session","part_file_operation","part_file_location","live_msg")) {
            String condition=table.equals("multipart_upload_part")?"session_id IN (SELECT id FROM multipart_upload_session WHERE part_id IN (SELECT id FROM record_history_part WHERE history_id=?))":"part_id IN (SELECT id FROM record_history_part WHERE history_id=?)";
            jdbc.update("DELETE FROM "+table+" WHERE "+condition,id);
        }
        jdbc.update("UPDATE bili_bili_user SET publish_captcha_probe_task_id=NULL,publish_captcha_retry_at=NULL WHERE publish_captcha_probe_task_id IN (SELECT id FROM publish_task WHERE history_id=?)",id);
        for(String table:List.of("publish_task","video_comment_task","video_visibility_restore_task","history_deletion_task","room_live_session_stats","room_live_msg_bucket_stats","room_live_danmu_user_stats","room_live_event","room_live_event_parse_state","room_live_event_xml_issue","stats_update_state"))jdbc.update("DELETE FROM "+table+" WHERE history_id=?",id);
        jdbc.update("UPDATE record_room SET history_id=NULL,session_id=NULL,recording=FALSE,streaming=FALSE WHERE history_id=?",id);
        jdbc.update("UPDATE record_history SET split_parent_id=NULL,split_boundary_part_id=NULL WHERE id=?",id);
        jdbc.update("UPDATE record_history SET split_boundary_part_id=NULL WHERE split_boundary_part_id IN (SELECT id FROM record_history_part WHERE history_id=?)",id);
        jdbc.update("DELETE FROM record_history_part WHERE history_id=?",id);
    }
    private static Object later(Object a,Object b) {
        LocalDateTime value=null;for(Object item:Arrays.asList(a,b))if(item!=null) {
            LocalDateTime next=((java.sql.Timestamp)BackupSchema.sql(BackupSchema.SECTIONS.get("userList").property("publishCooldownUntil"),item)).toLocalDateTime();
            if(next.isAfter(LocalDateTime.now()) && (value==null || next.isAfter(value)))value=next;
        }return value==null?null:value.toString();
    }
    private static Long longValue(Object value) {return value==null?null:Long.valueOf(value.toString());}
    private static boolean nonblank(Object value) {return value!=null && !value.toString().isBlank();}
    @jakarta.annotation.PreDestroy
    public void shutdown() {
        for(BackupStage stage:sessions.values())stage.cancelled.set(true);
        worker.shutdownNow();
        try {if(worker.awaitTermination(30,TimeUnit.SECONDS))for(BackupStage stage:sessions.values())stage.close();}
        catch(Exception ignored) {Thread.currentThread().interrupt();}
    }
}
