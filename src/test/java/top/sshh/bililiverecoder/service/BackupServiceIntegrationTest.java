package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.service.backup.BackupService;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BackupServiceIntegrationTest {
    static DriverManagerDataSource dataSource;
    static LocalContainerEntityManagerFactoryBean factory;
    static JdbcTemplate jdbc;
    @TempDir Path work;
    BackupService service;

    @BeforeAll static void schema() {
        dataSource=new DriverManagerDataSource("jdbc:h2:mem:backup-tests;DB_CLOSE_DELAY=-1","sa","");
        jdbc=new JdbcTemplate(dataSource);factory=new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);factory.setPackagesToScan("top.sshh.bililiverecoder.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.physical_naming_strategy","org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        factory.afterPropertiesSet();
    }
    @AfterAll static void close() {factory.destroy();}
    @BeforeEach void setup() throws Exception {
        for(String table:jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",String.class))jdbc.execute("DELETE FROM "+table);
        service=new BackupService(jdbc,new DatabaseMaintenanceState(),new DataSourceTransactionManager(dataSource),work.toString(),8L<<30,64L<<30);
    }
    @AfterEach void shutdown() {service.shutdown();}
    void persist(Object... values) {
        EntityManager em=factory.getObject().createEntityManager();em.getTransaction().begin();
        for(Object value:values)em.persist(value);em.getTransaction().commit();em.close();
    }
    Map<String,Object> fixture() {
        BiliBiliUser user=new BiliBiliUser();user.setUid(12345L);user.setUname("原投稿账号");user.setCookies("cookie");user.setPublishCaptchaProbeTaskId(987L);
        persist(user);
        RecordHistory history=new RecordHistory();history.setRoomId("10");history.setTitle("历史录制");history.setEventId(UUID.randomUUID().toString());
        history.setPublishUserId(user.getId());history.setRecording(true);history.setUpload(true);history.setEditPartsUploading(true);
        history.setPublishIssueType("TIMESTAMP_JUMP");history.setStartTime(LocalDateTime.of(2025,1,1,10,0));history.setEndTime(LocalDateTime.of(2025,1,1,11,0));persist(history);
        RecordHistoryPart part=new RecordHistoryPart();part.setHistoryId(history.getId());part.setRoomId("10");part.setFilePath("D:/old/video.flv");part.setRecording(true);part.setUpload(false);part.setDeleteFailType("FILE_MISSING");persist(part);
        RecordRoom room=new RecordRoom();room.setRoomId("10");room.setUploadUserId(user.getId());room.setHistoryId(history.getId());room.setRecording(true);room.setSessionId("old-session");persist(room);
        LiveMsg message=new LiveMsg();message.setPartId(part.getId());message.setContext("以前的弹幕");message.setCode(-1);message.setSend(false);persist(message);
        RoomLiveSessionStats stats=new RoomLiveSessionStats();stats.setHistoryId(history.getId());stats.setRoomId("10");stats.setMsgCount(8);stats.setLiveDate(java.time.LocalDate.of(2025,1,1));stats.setDurationSeconds(3600);persist(stats);
        RoomLiveDailyStats daily=new RoomLiveDailyStats();daily.setRoomId("10");daily.setLiveDate(stats.getLiveDate());daily.setTotalMsgCount(8);daily.setLiveCount(1);persist(daily);
        RoomLiveEvent event=new RoomLiveEvent();event.setHistoryId(history.getId());event.setPartId(part.getId());event.setRoomId("10");event.setType("GIFT");event.setRawJson("{\"original\":true}");persist(event);
        return Map.of("user",user,"history",history,"part",part);
    }
    BackupService.ExportArtifact export() throws Exception {
        ExportConfigParams p=new ExportConfigParams();p.setExportUser(true);p.setExportRoom(true);p.setExportSystemConfig(true);p.setExportHistory(true);p.setExportLiveMsg(true);p.setExportStats(true);
        return service.export(p);
    }
    String prepare(Path file) throws Exception {
        String id=service.create(Files.size(file)).get("taskId").toString();
        try(InputStream in=Files.newInputStream(file)) {byte[] block;int index=0;while((block=in.readNBytes(16*1024*1024)).length>0)service.chunk(id,index++,new ByteArrayInputStream(block));}
        service.prepare(id);await(id,"PREVIEW");return id;
    }
    Map<String,Object> await(String id,String expected) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {
            Map<String,Object> status=service.status(id);String phase=status.get("phase").toString();
            if(phase.equals(expected))return status;
            if(phase.equals("FAILED") || phase.equals("CANCELLED"))fail(status.toString());
            Thread.sleep(25);
        }
        fail("任务超时："+service.status(id));return null;
    }
    @Test void roundTripRemapsAccountsAndPreservesFactsWithoutResuming() throws Exception {
        var before=fixture();var artifact=export();
        for(String table:jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC'",String.class))jdbc.execute("DELETE FROM "+table);
        String id=prepare(artifact.path());service.commit(id,new BackupService.CommitRequest(Map.of()));var report=await(id,"DONE");
        Long user=jdbc.queryForObject("SELECT id FROM bili_bili_user WHERE uid=12345",Long.class);
        Map<String,Object> history=jdbc.queryForMap("SELECT * FROM record_history");
        assertEquals(user,history.get("PUBLISH_USER_ID"));assertEquals(12345L,history.get("ORIGINAL_PUBLISH_UID"));assertEquals(true,history.get("IMPORT_ARCHIVED"));
        assertEquals(false,history.get("RECORDING"));assertEquals(false,history.get("UPLOAD"));assertEquals(false,history.get("PUBLISH"));
        assertTrue(history.get("ARCHIVE_ORIGINAL_STATE").toString().contains("TIMESTAMP_JUMP"));
        assertNull(jdbc.queryForObject("SELECT history_id FROM record_room",Long.class));assertNull(jdbc.queryForObject("SELECT publish_captcha_probe_task_id FROM bili_bili_user",Long.class));
        assertEquals(-1,jdbc.queryForObject("SELECT code FROM live_msg",Integer.class));assertEquals(false,jdbc.queryForObject("SELECT is_send FROM live_msg",Boolean.class));
        assertEquals(8L,jdbc.queryForObject("SELECT total_msg_count FROM room_live_daily_stats",Long.class));
        assertEquals(true,jdbc.queryForObject("SELECT imported_snapshot FROM room_live_session_stats",Boolean.class));
        assertEquals("{\"original\":true}",jdbc.queryForObject("SELECT raw_json FROM room_live_event",String.class));
        assertNotEquals(((BiliBiliUser)before.get("user")).getId(),user);
        assertNotNull(report.get("result"));
        String repeated=prepare(artifact.path());service.commit(repeated,new BackupService.CommitRequest(Map.of()));await(repeated,"DONE");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM record_history",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM live_msg",Integer.class));
        assertEquals(8L,jdbc.queryForObject("SELECT total_msg_count FROM room_live_daily_stats",Long.class));
        Files.delete(artifact.path());
    }
    @Test void previewDoesNotWriteAndLegacyOrphansAreQuarantined() throws Exception {
        Map<String,Object> part=JSON.parseObject(JSON.toJSONString(new RecordHistoryPart()));part.put("id",55);part.put("historyId",404);
        Path file=work.resolve("legacy.json");Files.writeString(file,JSON.toJSONString(Map.of("partList",List.of(part))));
        String id=prepare(file);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM backup_quarantine_record",Integer.class));
        service.commit(id,new BackupService.CommitRequest(Map.of()));await(id,"DONE");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM record_history_part",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM backup_quarantine_record",Integer.class));
    }
    @Test void changedTargetRejectsCommitWithoutPartialWrites() throws Exception {
        fixture();var artifact=export();String id=prepare(artifact.path());
        jdbc.update("UPDATE record_history SET title='预览后修改'");service.commit(id,new BackupService.CommitRequest(Map.of()));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(service.status(id).get("phase").equals("APPLYING") && System.nanoTime()<deadline)Thread.sleep(25);
        assertEquals("FAILED",service.status(id).get("phase"));assertEquals("预览后修改",jdbc.queryForObject("SELECT title FROM record_history",String.class));
        assertEquals(false,jdbc.queryForObject("SELECT import_archived FROM record_history",Boolean.class));Files.delete(artifact.path());
    }
    @Test void replacementKeepsHistoryIdentityAndReplacesChildren() throws Exception {
        var before=fixture();var artifact=export();
        jdbc.update("UPDATE room_live_session_stats SET msg_count=99");jdbc.update("UPDATE room_live_daily_stats SET total_msg_count=99");
        String id=prepare(artifact.path());
        Long history=((RecordHistory)before.get("history")).getId();
        service.commit(id,new BackupService.CommitRequest(Map.of("historyList:"+history,"REPLACE")));await(id,"DONE");
        assertEquals(history,jdbc.queryForObject("SELECT id FROM record_history",Long.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM live_msg",Integer.class));
        assertEquals(true,jdbc.queryForObject("SELECT import_archived FROM record_history",Boolean.class));
        assertEquals(8L,jdbc.queryForObject("SELECT total_msg_count FROM room_live_daily_stats",Long.class));Files.delete(artifact.path());
    }
    Map<String,Object> awaitFailure(String id) throws Exception {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {
            var status=service.status(id);
            if(Set.of("FAILED","CANCELLED").contains(status.get("phase")))return status;
            Thread.sleep(25);
        }
        fail("未返回失败结果："+service.status(id));return null;
    }
    String upload(Path file) throws Exception {
        String id=service.create(Files.size(file)).get("taskId").toString();
        try(InputStream in=Files.newInputStream(file)) {byte[] block;int index=0;while((block=in.readNBytes(16*1024*1024)).length>0)service.chunk(id,index++,new ByteArrayInputStream(block));}
        service.prepare(id);return id;
    }
    @Test void invalidZipChecksumNeverWritesTarget() throws Exception {
        fixture();var artifact=export();Path damaged=work.resolve("damaged.zip");
        try(var source=new java.util.zip.ZipFile(artifact.path().toFile());var out=new java.util.zip.ZipOutputStream(Files.newOutputStream(damaged))) {
            var entries=source.entries();while(entries.hasMoreElements()) {
                var entry=entries.nextElement();out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                try(var input=source.getInputStream(entry)) {input.transferTo(out);}
                if(entry.getName().equals("liveMsgList.jsonl"))out.write("{}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        var status=awaitFailure(upload(damaged));assertTrue(status.get("message").toString().contains("校验"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM live_msg",Integer.class));Files.delete(artifact.path());
    }
    @Test void lateUniqueViolationRollsBackWholeImport() throws Exception {
        RecordHistory history=new RecordHistory();history.setId(10L);history.setRoomId("10");
        Map<String,Object> h=JSON.parseObject(JSON.toJSONString(history));h.put("backupKey",UUID.randomUUID().toString());
        RecordHistoryPart part=new RecordHistoryPart();part.setHistoryId(10L);part.setRoomId("10");
        Map<String,Object> first=JSON.parseObject(JSON.toJSONString(part));first.put("id",20);first.put("backupKey",UUID.randomUUID().toString());
        Map<String,Object> second=new LinkedHashMap<>(first);second.put("id",21);
        Path file=work.resolve("duplicate.json");Files.writeString(file,JSON.toJSONString(Map.of("historyList",List.of(h),"partList",List.of(first,second))));
        String id=prepare(file);service.commit(id,new BackupService.CommitRequest(Map.of()));awaitFailure(id);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM record_history",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM record_history_part",Integer.class));
        assertTrue(Files.exists(work.resolve("backup/before-import-"+id+".zip")));
    }
    @Test void legacyV2RequiresCompletionAndCounts() throws Exception {
        Path file=work.resolve("v2.json");Files.writeString(file,"{\"configFormatVersion\":2,\"historyList\":[],\"exportCompleted\":false}");
        assertEquals("FAILED",awaitFailure(upload(file)).get("phase"));
        Files.writeString(file,"{\"configFormatVersion\":2,\"historyList\":[],\"sectionCounts\":{\"historyList\":1},\"exportCompleted\":true}");
        assertTrue(awaitFailure(upload(file)).get("message").toString().contains("数量"));
    }
    @Test void cancellationClosesStageAndCommitIsIdempotent() throws Exception {
        fixture();var artifact=export();String id=prepare(artifact.path());Path directory=service.session(id).directory;
        service.cancel(id);assertEquals("CANCELLED",service.status(id).get("phase"));assertFalse(Files.exists(directory));
        service.commit(id,new BackupService.CommitRequest(Map.of()));assertEquals(false,jdbc.queryForObject("SELECT import_archived FROM record_history",Boolean.class));Files.delete(artifact.path());
    }
    @Test void legacySplitAndStorageReferencesAreRemapped() throws Exception {
        RecordHistory parent=new RecordHistory();parent.setId(10L);parent.setRoomId("10");parent.setEventId("parent");
        RecordHistory child=new RecordHistory();child.setId(11L);child.setRoomId("10");child.setEventId("child");child.setSplitParentId(10L);child.setSplitBoundaryPartId(21L);
        RecordHistoryPart p1=new RecordHistoryPart();p1.setId(20L);p1.setHistoryId(10L);p1.setRoomId("10");
        RecordHistoryPart p2=new RecordHistoryPart();p2.setId(21L);p2.setHistoryId(11L);p2.setRoomId("10");
        StorageRoot root=new StorageRoot();root.setId(30L);root.setRootKey("old-work");root.setPath("D:/old");root.setRootType(StorageRoot.RootType.WORK);
        PartFileLocation location=new PartFileLocation();location.setId(40L);location.setPartId(21L);location.setStorageRootId(30L);location.setRelativePath("video.flv");location.setRole(PartFileLocation.LocationRole.PRIMARY);location.setState(PartFileLocation.LocationState.AVAILABLE);
        Path file=work.resolve("split.json");Files.writeString(file,JSON.toJSONString(Map.of("historyList",List.of(parent,child),"partList",List.of(p1,p2),"storageRootList",List.of(root),"partFileLocationList",List.of(location))));
        String id=prepare(file);service.commit(id,new BackupService.CommitRequest(Map.of()));await(id,"DONE");
        Long targetParent=jdbc.queryForObject("SELECT id FROM record_history WHERE event_id='parent'",Long.class);
        var targetChild=jdbc.queryForMap("SELECT * FROM record_history WHERE event_id='child'");assertEquals(targetParent,targetChild.get("SPLIT_PARENT_ID"));
        Long targetPart=jdbc.queryForObject("SELECT id FROM record_history_part WHERE history_id=?",Long.class,targetChild.get("ID"));assertEquals(targetPart,targetChild.get("SPLIT_BOUNDARY_PART_ID"));
        assertEquals(targetPart,jdbc.queryForObject("SELECT part_id FROM part_file_location",Long.class));assertEquals("OFFLINE",jdbc.queryForObject("SELECT status FROM storage_root",String.class));
    }
    @Test void missingNotificationChannelIsQuarantinedWithoutDroppingIds() throws Exception {
        NotificationRule rule=new NotificationRule();rule.setId(10L);rule.setChannelIds("999");rule.setRoomId("*");rule.setEventType("TEST");
        Path file=work.resolve("rule.json");Files.writeString(file,JSON.toJSONString(Map.of("notificationRuleList",List.of(rule))));
        String id=prepare(file);service.commit(id,new BackupService.CommitRequest(Map.of()));await(id,"DONE");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM notification_rule",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM backup_quarantine_record",Integer.class));
        assertTrue(service.quarantinePayload(jdbc.queryForObject("SELECT id FROM backup_quarantine_record",Long.class)).contains("999"));
        assertFalse(((List<Map<String,Object>>)service.quarantined(0).get("records")).get(0).containsKey("payload"));
    }

    @Test void importedPublishedPartsNeverEnterAutomaticQueues() throws Exception {
        var fixture=fixture();Long history=((RecordHistory)fixture.get("history")).getId();
        jdbc.update("UPDATE record_history SET import_archived=TRUE,publish=TRUE,code=0,bv_id='BVold' WHERE id=?",history);
        EntityManager em=factory.getObject().createEntityManager();
        try {
            var repositories=new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(em);
            var parts=repositories.getRepository(top.sshh.bililiverecoder.repo.RecordHistoryPartRepository.class);
            var histories=repositories.getRepository(top.sshh.bililiverecoder.repo.RecordHistoryRepository.class);
            var messages=repositories.getRepository(top.sshh.bililiverecoder.repo.LiveMsgRepository.class);
            assertTrue(parts.findOpenCandidates(org.springframework.data.domain.PageRequest.of(0,100)).isEmpty());
            assertTrue(parts.findOrphanedPartsOfPublishedHistories().isEmpty());
            assertTrue(histories.findByPublishIsTrueAndSendReplyIsFalseAndCodeIn(List.of(0,-50)).isEmpty());
            assertTrue(histories.findSyncList().isEmpty());
            assertTrue(messages.findDistinctPartIdByCode(-1).isEmpty());
        } finally {em.close();}
    }
    @Test void chunkedLegacyInputLargerThanOld512MiBLimitIsAccepted() throws Exception {
        Path file=work.resolve("large-legacy.json");
        try(var out=new BufferedOutputStream(Files.newOutputStream(file))) {
            byte[] padding=new byte[1024*1024];Arrays.fill(padding,(byte)' ');
            for(int i=0;i<513;i++)out.write(padding);
            out.write("{\"historyList\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(Files.size(file)>512L*1024*1024);
        String id=prepare(file);assertEquals("PREVIEW",service.status(id).get("phase"));service.cancel(id);
    }

    @Test void appendingChildKeepsBoundaryReferenceToPreservedParentPart() throws Exception {
        var original=fixture();RecordHistory parent=(RecordHistory)original.get("history");RecordHistoryPart part=(RecordHistoryPart)original.get("part");
        RecordHistory child=new RecordHistory();child.setRoomId("10");child.setEventId("new-child");child.setSplitParentId(parent.getId());child.setSplitBoundaryPartId(part.getId());persist(child);
        var backup=export();jdbc.update("DELETE FROM record_history WHERE id=?",child.getId());
        String id=prepare(backup.path());service.commit(id,new BackupService.CommitRequest(Map.of()));await(id,"DONE");
        var restored=jdbc.queryForMap("SELECT split_parent_id,split_boundary_part_id FROM record_history WHERE event_id='new-child'");
        assertEquals(parent.getId(),restored.get("SPLIT_PARENT_ID"));assertEquals(part.getId(),restored.get("SPLIT_BOUNDARY_PART_ID"));Files.delete(backup.path());
    }
    @Test void replacingLegacyParentRemapsBoundaryOfKeptChild() throws Exception {
        var original=fixture();RecordHistory parent=(RecordHistory)original.get("history");RecordHistoryPart part=(RecordHistoryPart)original.get("part");
        RecordHistory child=new RecordHistory();child.setRoomId("10");child.setEventId("kept-child");child.setForceArchived(true);child.setSplitParentId(parent.getId());child.setSplitBoundaryPartId(part.getId());persist(child);
        var backup=export();Map<String,Object> payload=new LinkedHashMap<>();
        try(var zip=new java.util.zip.ZipFile(backup.path().toFile())) {
            var entries=zip.entries();while(entries.hasMoreElements()) {
                var entry=entries.nextElement();if(!entry.getName().endsWith(".jsonl"))continue;
                try(var reader=new BufferedReader(new InputStreamReader(zip.getInputStream(entry),java.nio.charset.StandardCharsets.UTF_8))) {
                    List<Map<String,Object>> rows=reader.lines().map(JSON::parseObject).map(data->(Map<String,Object>)data).toList();
                    if(entry.getName().equals("partList.jsonl"))rows.forEach(data->data.remove("backupKey"));
                    payload.put(entry.getName().replace(".jsonl",""),rows);
                }
            }
        }
        Path file=work.resolve("legacy-parent.json");Files.writeString(file,JSON.toJSONString(payload));Files.delete(backup.path());
        String id=prepare(file);service.commit(id,new BackupService.CommitRequest(Map.of("historyList:"+parent.getId(),"REPLACE")));await(id,"DONE");
        Long newPart=jdbc.queryForObject("SELECT id FROM record_history_part WHERE history_id=?",Long.class,parent.getId());assertNotEquals(part.getId(),newPart);
        assertEquals(part.getBackupKey(),jdbc.queryForObject("SELECT backup_key FROM record_history_part WHERE id=?",String.class,newPart));
        var kept=jdbc.queryForMap("SELECT * FROM record_history WHERE id=?",child.getId());assertEquals(newPart,kept.get("SPLIT_BOUNDARY_PART_ID"));assertEquals(true,kept.get("FORCE_ARCHIVED"));assertEquals(false,kept.get("IMPORT_ARCHIVED"));
    }

    @Test void freshDatabaseRestoresUserSettingsOverBootstrapDefaults() throws Exception {
        jdbc.update("INSERT INTO system_config(config_key,config_value,description) VALUES('MERGE_INTERVAL','10','default')");
        Path file=work.resolve("settings.json");Files.writeString(file,JSON.toJSONString(Map.of("systemConfigList",List.of(Map.of("configKey","MERGE_INTERVAL","configValue","30","description","以前的设置")))));
        String id=prepare(file);assertEquals(true,service.preview(id,0).get("emptyTarget"));
        service.commit(id,new BackupService.CommitRequest(Map.of("systemConfigList:MERGE_INTERVAL","REPLACE")));await(id,"DONE");
        assertEquals("30",jdbc.queryForObject("SELECT config_value FROM system_config WHERE config_key='MERGE_INTERVAL'",String.class));
    }

}
