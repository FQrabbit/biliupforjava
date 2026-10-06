package top.sshh.bililiverecoder.service.backup;

import jakarta.persistence.*;
import top.sshh.bililiverecoder.entity.*;
import java.lang.reflect.Field;
import java.time.*;
import java.sql.*;
import java.util.*;

/** 备份字段使用固定清单，实体的展示字段和以后新增字段不会自动进入备份 */
public final class BackupSchema {
    public record Property(String name, String column, Class<?> type, boolean ordinal) {}
    public record Section(String name, String table, String primary, boolean generated, List<Property> properties) {
        public String columns() { return String.join(",", properties.stream().map(Property::column).toList()); }
        public Property property(String name) { return properties.stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow(); }
    }
    public static final Map<String, Section> SECTIONS = new LinkedHashMap<>();
    private static final Map<String, Map<String,Object>> DEFAULTS = new HashMap<>();
    static {
        add("userList", "bili_bili_user", BiliBiliUser.class, "id,uid,uname,face,accessToken,refreshToken,cookies,updateTime,login,enable,enableSc,publishRiskFailures,publishCooldownUntil,publishLastRiskAt,publishSuccessStreak,publishNextAllowedAt,publishCaptchaProbeTaskId,publishCaptchaRetryAt");
        add("roomList", "record_room", RecordRoom.class, "id,roomId,uname,historyId,uploadUserId,upload,title,titleTemplate,fileSizeLimit,durationLimit,tags,tid,userCover,userCoverUpdateTime,anchorId,gender,liveCoverUrl,copyright,percentileRank,highEnergyCut,seasonId,sectionId,isOnlySelf,noDisturbance,coverUrl,line,wxuid,serverChanSendKey,serverChanChannel,pushMsgTags,descTemplate,dynamicTemplate,partTitleTemplate,deleteType,deleteDay,moveDir,sessionId,webhookSource,webhookLastSeenAt,sendDm,sendSc,sendGiftReply,giftReplyMinPriceCny,dmDistinct,dmUlLevel,dmFanMedal,recording,streaming,sortOrder,createTime,updateTime,dmKeywordBlacklist");
        add("storageRootList", "storage_root", StorageRoot.class, "id,rootKey,rootType,path,status,activeForNewFiles,writable,createdAt,updatedAt,lastCheckedAt");
        add("historyList", "record_history", RecordHistory.class, "id,backupKey,importArchived,importedAt,importBatchId,originalPublishUid,archiveOriginalState,roomId,avId,bvId,title,coverUrl,localCoverPath,eventId,sessionId,splitDurationSeconds,splitSizeBytes,splitGroup,splitSequence,splitParentId,splitBoundaryPartId,splitReason,splitClosedAt,filePath,fileSize,recording,streaming,upload,publish,sendReply,forceArchived,deletePending,publishUserId,code,editPartsUploading,uploadRetryCount,uploadPaused,uploadPausedAt,uploadPauseReason,publishIssueType,publishIssueReason,publishIssuePartCount,startTime,endTime,closeSource,closeAt,updateTime");
        add("partList", "record_history_part", RecordHistoryPart.class, "id,backupKey,archiveOriginalState,roomId,historyId,cid,liveTitle,title,areaName,filePath,danmakuFilePath,page,partOrder,sourcePartOrder,duration,fileName,fileSize,eventId,sessionId,splitFileSize,splitDuration,splitAssigned,recording,upload,fileDelete,deleteRetryCount,deleteFailReason,deleteFailType,uploadRetryCount,uploadFlow,uploadFlowFallback,uploadFlowFallbackReason,uploadPaused,uploadPausedAt,uploadPauseReason,startTime,endTime,closeSource,autoCloseAt,autoCloseFileSize,autoCloseFileModifiedAt,updateTime,isPost,sourceType");
        add("partFileLocationList", "part_file_location", PartFileLocation.class, "id,partId,storageRootId,relativePath,absolutePathSnapshot,role,state,expectedSize,lastVerifiedAt,createdAt,updatedAt,errorMessage");
        add("systemConfigList", "system_config", SystemConfig.class, "configKey,configValue,description");
        add("notificationChannelList", "notification_channel", NotificationChannel.class, "id,name,type,enabled,configJson,secretJson,createTime,updateTime");
        add("notificationRuleList", "notification_rule", NotificationRule.class, "id,eventType,eventLabel,roomId,roomName,enabled,channelIds,createTime,updateTime");
        add("liveMsgList", "live_msg", LiveMsg.class, "id,partId,bvid,cid,context,color,fontsize,pool,mode,sendTime,isSend,code");
        add("roomLiveSessionStatsList", "room_live_session_stats", RoomLiveSessionStats.class, "id,historyId,roomId,uname,title,bvId,liveDate,startHour,startTime,endTime,durationSeconds,partCount,fileSize,uploadEnabled,published,publishCode,sendReply,msgCount,normalMsgCount,advancedMsgCount,giftEventCount,giftTotalCount,giftTotalCoin,giftAmountCny,giftTypeCount,scCount,scAmount,guardCount,activeUserCount,peakMinuteMsgCount,peakMinuteIndex,statsUpdatedAt,statsVersion,importedSnapshot");
        add("roomLiveDailyStatsList", "room_live_daily_stats", RoomLiveDailyStats.class, "id,roomId,uname,liveDate,liveCount,totalDurationSeconds,averageDurationSeconds,totalFileSize,totalMsgCount,totalNormalMsgCount,totalAdvancedMsgCount,totalGiftEventCount,totalGiftCount,totalGiftCoin,totalGiftAmountCny,totalScCount,totalScAmount,totalGuardCount,totalActiveUserCount,publishedCount,successfulPublishCount,statsUpdatedAt,statsVersion");
        add("roomLiveMsgBucketStatsList", "room_live_msg_bucket_stats", RoomLiveMsgBucketStats.class, "id,historyId,roomId,bucketIndex,bucketStartMs,msgCount,normalMsgCount,advancedMsgCount,giftEventCount,scCount,guardCount,statsUpdatedAt,statsVersion");
        add("roomLiveDanmuUserStatsList", "room_live_danmu_user_stats", RoomLiveDanmuUserStats.class, "id,historyId,partId,roomId,liveDate,uid,uname,danmuCount,statsUpdatedAt,parserVersion");
        add("roomLiveEventList", "room_live_event", RoomLiveEvent.class, "id,historyId,partId,roomId,liveDate,type,uid,uname,sendTime,content,rawJson,giftId,giftName,giftCount,giftPriceCoin,giftTotalCoin,giftCoinType,scPrice,scDisplaySeconds,guardLevel,guardCount,createdAt");
        add("roomLiveEventParseStateList", "room_live_event_parse_state", RoomLiveEventParseState.class, "id,partId,historyId,roomId,xmlPath,xmlLastModified,xmlSize,eventCount,danmuCount,giftCount,scCount,guardCount,success,errorMessage,parsedAt,parserVersion");
        add("roomLiveEventXmlIssueList", "room_live_event_xml_issue", RoomLiveEventXmlIssue.class, "partId,historyId,roomId,issueType,storageRootId,xmlPath,errorMessage,firstDetectedAt,lastCheckedAt,ignoredAt");
        add("roomLiveGiftCatalogList", "room_live_gift_catalog", RoomLiveGiftCatalog.class, "id,roomId,giftId,giftName,priceCoin,priceCny,updatedAt");
        add("quarantineList", "backup_quarantine_record", BackupQuarantineRecord.class, "id,batchId,section,sourceKey,reason,payload,createdAt");
    }
    private BackupSchema() {}
    private static void add(String name, String table, Class<?> type, String fields) {
        List<Property> properties = new ArrayList<>();
        String primary = null;
        boolean generated = false;
        try {
            Object defaults=type.getDeclaredConstructor().newInstance();
            Map<String,Object> values=new LinkedHashMap<>();
            for (String fieldName : fields.split(",")) {
                Field f = type.getDeclaredField(fieldName);
                f.setAccessible(true);values.put(fieldName,f.get(defaults));
                Column column = f.getAnnotation(Column.class);
                String sqlName = column != null && !column.name().isBlank() ? column.name() : snake(fieldName);
                Enumerated enumeration = f.getAnnotation(Enumerated.class);
                properties.add(new Property(fieldName, sqlName, f.getType(), f.getType().isEnum()
                        && (enumeration == null || enumeration.value() == EnumType.ORDINAL)));
                if (f.isAnnotationPresent(Id.class)) { primary = fieldName; generated = f.isAnnotationPresent(GeneratedValue.class); }
            }
            DEFAULTS.put(name,values);
            SECTIONS.put(name, new Section(name, table, primary, generated, List.copyOf(properties)));
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    public static void fillDefaults(String section,Map<String,Object> data) {
        for(var property:SECTIONS.get(section).properties()) {
            if(!data.containsKey(property.name()) || property.type().isPrimitive() && data.get(property.name())==null)
                data.put(property.name(),DEFAULTS.get(section).get(property.name()));
        }
    }
    public static String snake(String value) { return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT); }
    public static Object read(ResultSet rs, Property p) throws SQLException {
        Object value = rs.getObject(p.column());
        if (value instanceof Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
        if (value instanceof Timestamp stamp) value = stamp.toLocalDateTime().toString();
        if (value instanceof java.sql.Date date) value = date.toLocalDate().toString();
        if (p.type().isEnum() && value != null && p.ordinal()) value = p.type().getEnumConstants()[((Number)value).intValue()].toString();
        return value;
    }
    public static Object sql(Property p, Object value) {
        if (value == null) return null;
        if (p.type() == LocalDateTime.class) {
            if (value instanceof Number n) return Timestamp.from(Instant.ofEpochMilli(n.longValue()));
            return Timestamp.valueOf(LocalDateTime.parse(value.toString().replace(' ', 'T')));
        }
        if (p.type() == LocalDate.class) {
            if (value instanceof Number n) return java.sql.Date.valueOf(Instant.ofEpochMilli(n.longValue()).atZone(ZoneId.systemDefault()).toLocalDate());
            return java.sql.Date.valueOf(value.toString());
        }
        if (p.type() == boolean.class || p.type() == Boolean.class) return value instanceof Number n ? n.intValue()!=0 : Boolean.parseBoolean(value.toString());
        if (p.type().isEnum()) {
            Object[] values=p.type().getEnumConstants();
            for (int i=0;i<values.length;i++) if (values[i].toString().equals(value.toString())) return p.ordinal() ? i : value.toString();
            throw new IllegalArgumentException("备份枚举值无效：" + p.name());
        }
        return value;
    }
}
