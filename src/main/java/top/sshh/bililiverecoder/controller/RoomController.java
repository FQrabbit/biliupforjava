package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONReader;
import com.alibaba.fastjson.TypeReference;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliUserCard;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;
import top.sshh.bililiverecoder.repo.StorageRootRepository;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.service.StorageRootService;
import top.sshh.bililiverecoder.service.StorageLifecycleMigrationService;
import top.sshh.bililiverecoder.service.RoomDeletionService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.ImageDimensionsReader;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;
import top.sshh.bililiverecoder.util.UploadEnums;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@RestController
@RequestMapping("/room")
@Slf4j
public class RoomController {
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Autowired
    private SystemConfigRepository systemConfigRepository;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private LiveMsgRepository liveMsgRepository;

    @Autowired
    private StorageRootService storageRootService;

    @Autowired
    private StorageRootRepository storageRootRepository;

    @Autowired
    private PartFileLocationRepository partFileLocationRepository;

    @Autowired
    private StorageLifecycleMigrationService storageLifecycleMigrationService;

    @Autowired
    private RoomDeletionService roomDeletionService;

    private final Cache<String, CachedImage> imageCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();
    private static final long ROOM_AVATAR_CACHE_DAYS = 30;
    private final Map<String, CompletableFuture<CachedImage>> imageInflight = new ConcurrentHashMap<>();
    private final Semaphore imageProxySemaphore = new Semaphore(3, true);
    private final Semaphore avatarProxySemaphore = new Semaphore(8, true);
    // 记录上一次请求B站的时间戳
    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private volatile ConfigTaskStatus configTaskStatus = ConfigTaskStatus.idle();

    @Data
    @AllArgsConstructor
    private static class CachedImage {
        private byte[] bytes;
        private MediaType contentType;
    }

    private record SeasonSectionFixResult(Long seasonId, Long sectionId, String action) {}

    private record ConfigTaskStatus(String taskId,
                                    String task,
                                    String title,
                                    boolean running,
                                    boolean success,
                                    String phase,
                                    String message,
                                    String detail,
                                    long processed,
                                    long total,
                                    int percent,
                                    LocalDateTime updatedAt) {
        private static ConfigTaskStatus idle() {
            return new ConfigTaskStatus(null, "idle", "空闲", false, true, "IDLE",
                    "当前没有导入导出任务", "", 0, 0, 0, LocalDateTime.now());
        }

        private static ConfigTaskStatus start(String task, String title, long total) {
            return new ConfigTaskStatus(UUID.randomUUID().toString(), task, title, true, true, "STARTING",
                    "正在启动任务", "", 0, Math.max(0, total), 1, LocalDateTime.now());
        }

        private ConfigTaskStatus progress(String phase, String detail, long processed) {
            long safeTotal = Math.max(0, total);
            long safeProcessed = Math.max(0, safeTotal > 0 ? Math.min(processed, safeTotal) : processed);
            int nextPercent = safeTotal <= 0 ? 5 : Math.max(1, Math.min(99, (int) Math.floor(safeProcessed * 100.0d / safeTotal)));
            return new ConfigTaskStatus(taskId, task, title, true, true, phase, phase,
                    detail == null ? "" : detail, safeProcessed, safeTotal, nextPercent, LocalDateTime.now());
        }

        private ConfigTaskStatus done(String message) {
            return new ConfigTaskStatus(taskId, task, title, false, true, "DONE", message,
                    detail, total, total, 100, LocalDateTime.now());
        }

        private ConfigTaskStatus failed(String message) {
            return new ConfigTaskStatus(taskId, task, title, false, false, "FAILED", message,
                    detail, processed, total, 100, LocalDateTime.now());
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("taskId", taskId);
            map.put("task", task);
            map.put("title", title);
            map.put("running", running);
            map.put("success", success);
            map.put("phase", phase);
            map.put("message", message);
            map.put("detail", detail);
            map.put("processed", processed);
            map.put("total", total);
            map.put("percent", percent);
            map.put("updatedAt", updatedAt);
            return map;
        }
    }

    @GetMapping("/configTask/status")
    public Map<String, Object> configTaskStatus() {
        return configTaskStatus.toMap();
    }

    private void startConfigTask(String task, String title, long total) {
        configTaskStatus = ConfigTaskStatus.start(task, title, total);
    }

    private void updateConfigTask(String phase, String detail, long processed) {
        configTaskStatus = configTaskStatus.progress(phase, detail, processed);
    }

    private void finishConfigTask(String message) {
        configTaskStatus = configTaskStatus.done(message);
    }

    private void failConfigTask(String message) {
        configTaskStatus = configTaskStatus.failed(message);
    }


    @PostMapping
    public List<RecordRoom> list() {
        List<RecordRoom> rooms = roomRepository.findAllOrderBySortOrder();
        refreshRoomAvatarCacheIfNeeded(rooms);
        return rooms;
    }

    private void refreshRoomAvatarCacheIfNeeded(List<RecordRoom> rooms) {
        if (rooms == null || rooms.isEmpty()) {
            return;
        }
        List<RecordRoom> pending = rooms.stream()
                .filter(room -> room != null && room.getAnchorId() != null)
                .filter(room -> StringUtils.isBlank(room.getUserCover()) || isRoomAvatarCacheExpired(room))
                .toList();
        if (pending.isEmpty()) {
            return;
        }

        List<RecordRoom> changed = new ArrayList<>();
        String cookie = findUsableBiliUserCookie();
        for (int from = 0; from < pending.size(); from += 50) {
            List<RecordRoom> batch = pending.subList(from, Math.min(from + 50, pending.size()));
            try {
                Map<Long, BiliUserCard> cards = BiliApi.getUserCards(batch.stream().map(RecordRoom::getAnchorId).toList(), cookie);
                for (RecordRoom room : batch) {
                    BiliUserCard card = cards.get(room.getAnchorId());
                    if (card == null || StringUtils.isBlank(card.getFace())) {
                        continue;
                    }
                    boolean updated = false;
                    if (!card.getFace().equals(room.getUserCover())) {
                        room.setUserCover(card.getFace());
                        updated = true;
                    }
                    if (updated || isRoomAvatarCacheExpired(room)) {
                        room.setUserCoverUpdateTime(LocalDateTime.now());
                        changed.add(room);
                    }
                }
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("Room.Avatar.BatchRefreshFailed")
                        .add("batchSize", batch.size())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }
        }
        if (!changed.isEmpty()) {
            roomRepository.saveAll(changed);
        }
    }

    private boolean isRoomAvatarCacheExpired(RecordRoom room) {
        LocalDateTime updatedAt = room.getUserCoverUpdateTime();
        return updatedAt == null || updatedAt.isBefore(LocalDateTime.now().minusDays(ROOM_AVATAR_CACHE_DAYS));
    }

    private String findUsableBiliUserCookie() {
        for (BiliBiliUser user : userRepository.findAll()) {
            if (user != null && StringUtils.isNotBlank(user.getCookies())) {
                return user.getCookies();
            }
        }
        return null;
    }


    /**
     * 流式导出配置：直接从数据库逐条读取实体并写入 HTTP 响应输出流，
     * 不在内存中构建完整的 Map 或 JSON String，避免大文件（如百万级弹幕）导致 OOM。
     */
    @Transactional(readOnly = true)
    @PostMapping("/exportConfig")
    public void exportConfig(@RequestBody ExportConfigParams params, HttpServletResponse response) throws IOException {
        long total = 1;
        if (params.isExportRoom()) total += roomRepository.count();
        if (params.isExportUser()) total += userRepository.count();
        if (params.isExportHistory()) total += historyRepository.count() + partRepository.count()
                + storageRootRepository.count() + partFileLocationRepository.count();
        if (params.isExportSystemConfig()) total += systemConfigRepository.count();
        if (params.isExportLiveMsg()) total += liveMsgRepository.count();
        long processed = 0;
        startConfigTask("export", "导出配置", total);

        String timeString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分"));
        String encodedFilename = URLEncoder.encode("biliupForJavaConfig_"+timeString+".json", StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        response.setContentType("application/json; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename="+encodedFilename);

        try (OutputStream out = response.getOutputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 65536)) {
            writer.write("{");
            boolean[] firstSection = {true};

            // userList 必须在 roomList 之前导出，确保流式导入时 ID 映射可用
            if(params.isExportUser()){
                updateConfigTask("导出用户", "正在读取用户配置", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "userList");
                long[] localProcessed = {processed};
                int count = writeEntityList(writer, userRepository.findAll().iterator(),
                        idx -> { localProcessed[0]++; if (localProcessed[0] % 500 == 0)
                            updateConfigTask("导出用户", "已读取 " + idx + " 个用户", localProcessed[0]); });
                processed = localProcessed[0];
                writer.write("]");
                updateConfigTask("导出用户", "用户 " + count + " 条", processed);
            }
            if(params.isExportRoom()){
                updateConfigTask("导出房间", "正在读取房间配置", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "roomList");
                int count = writeEntityList(writer, roomRepository.findAll().iterator(), null);
                processed += count;
                writer.write("]");
                updateConfigTask("导出房间", "房间 " + count + " 条", processed);
            }
            if(params.isExportHistory()){
                updateConfigTask("导出存储根", "正在读取存储根", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "storageRootList");
                int count = writeEntityStream(writer, storageRootRepository.streamAll(), null);
                processed += count;
                writer.write("]");

                updateConfigTask("导出历史", "正在读取录制历史", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "historyList");
                long[] localProcessed = {processed};
                count = writeEntityStream(writer, historyRepository.streamAll(),
                        idx -> { localProcessed[0]++; if (localProcessed[0] % 1000 == 0)
                            updateConfigTask("导出历史", "已读取 " + idx + " 场历史", localProcessed[0]); });
                processed = localProcessed[0];
                writer.write("]");
                updateConfigTask("导出历史", "历史 " + count + " 条", processed);

                updateConfigTask("导出分P", "正在读取分P记录", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "partList");
                localProcessed[0] = processed;
                count = writeEntityStream(writer, partRepository.streamAll(),
                        idx -> { localProcessed[0]++; if (localProcessed[0] % 1000 == 0)
                            updateConfigTask("导出分P", "已读取 " + idx + " 个分P", localProcessed[0]); });
                processed = localProcessed[0];
                writer.write("]");
                updateConfigTask("导出分P", "分P " + count + " 条", processed);

                updateConfigTask("导出文件位置", "正在读取分P文件位置", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "partFileLocationList");
                localProcessed[0] = processed;
                count = writeEntityStream(writer, partFileLocationRepository.streamCompleted(
                                PartFileLocation.LocationState.PROCESSING),
                        idx -> { localProcessed[0]++; if (localProcessed[0] % 1000 == 0)
                            updateConfigTask("导出文件位置", "已读取 " + idx + " 个位置", localProcessed[0]); });
                processed = localProcessed[0];
                writer.write("]");
            }
            if(params.isExportSystemConfig()){
                updateConfigTask("导出系统配置", "正在读取系统配置", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "systemConfigList");
                int count = writeEntityList(writer, systemConfigRepository.findAll().iterator(), null);
                processed += count;
                writer.write("]");
                updateConfigTask("导出系统配置", "系统配置 " + count + " 条", processed);
            }
            if(params.isExportLiveMsg()){
                updateConfigTask("导出弹幕", "正在读取弹幕数据", processed);
                firstSection[0] = writeSectionKey(writer, firstSection[0], "liveMsgList");
                long[] localProcessed = {processed};
                int count = writeEntityStream(writer, liveMsgRepository.streamAll(),
                        idx -> { localProcessed[0]++; if (localProcessed[0] % 5000 == 0)
                            updateConfigTask("导出弹幕", "已读取 " + idx + " 条弹幕", localProcessed[0]); });
                processed = localProcessed[0];
                writer.write("]");
                updateConfigTask("导出弹幕", "弹幕 " + count + " 条", processed);
            }

            writer.write("}");
            writer.flush();
            if (params.isExportLiveMsg()) {
                finishConfigTask("导出完成。注意：含弹幕数据的配置文件可能超过导入上限（"
                        + (MAX_CONFIG_IMPORT_SIZE / 1024 / 1024) + "MB），届时将无法导回。"
                        + "如文件过大，请重新导出时不勾选弹幕数据。");
            } else {
                finishConfigTask("导出完成");
            }
        } catch (IOException | RuntimeException e) {
            failConfigTask("导出失败：" + e.getMessage());
            throw e;
        }
    }

    /** 写入 JSON section key，如 ,"roomList":[，返回 false 表示已写过 section */
    private static boolean writeSectionKey(BufferedWriter writer, boolean isFirst, String key) throws IOException {
        if (!isFirst) writer.write(",");
        writer.write("\""); writer.write(key); writer.write("\":[");
        return false;
    }

    /** 从 Iterator 逐条序列化实体写入，返回写入数量 */
    private <T> int writeEntityList(BufferedWriter writer, Iterator<T> iterator,
                                    java.util.function.IntConsumer progressCallback) throws IOException {
        int count = 0;
        boolean first = true;
        while (iterator.hasNext()) {
            if (!first) writer.write(",");
            writer.write(JSON.toJSONString(iterator.next()));
            first = false;
            count++;
            if (progressCallback != null) progressCallback.accept(count);
        }
        return count;
    }

    /** 从 Stream 逐条序列化实体写入（用于大表流式读取），返回写入数量 */
    private <T> int writeEntityStream(BufferedWriter writer, Stream<T> stream,
                                      java.util.function.IntConsumer progressCallback) throws IOException {
        int[] count = {0};
        boolean[] first = {true};
        try (stream) {
            stream.forEach(entity -> {
                try {
                    if (!first[0]) writer.write(",");
                    writer.write(JSON.toJSONString(entity));
                    first[0] = false;
                    count[0]++;
                    if (progressCallback != null) progressCallback.accept(count[0]);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return count[0];
    }

    /**
     * 配置导入最大尺寸：128MB。
     * 使用 JSONReader 流式解析：只保持当前 section 在内存中，逐条反序列化并分批 saveAll，
     * 不再一次性加载整个文件 byte[] / Map / List。
     */
    private static final long MAX_CONFIG_IMPORT_SIZE = 128L * 1024 * 1024;

    /** 流式导入时每批保存的实体数量，与 JPA batch_size 对齐 */
    private static final int IMPORT_BATCH_SIZE = 1000;

    @PostMapping("/uploadConfig")
    public void uploadConfig(@RequestParam("file") MultipartFile file) throws IOException {
        long fileSize = file.getSize();
        if (fileSize > MAX_CONFIG_IMPORT_SIZE) {
            throw new IOException("配置文件过大（" + (fileSize / 1024 / 1024) + "MB），"
                    + "最大支持 " + (MAX_CONFIG_IMPORT_SIZE / 1024 / 1024) + "MB。"
                    + "请重新导出时减少弹幕数据范围或暂不包含弹幕数据");
        }
        long totalStartNs = System.nanoTime();
        long importUsersStartNs = 0L;
        long importRoomsStartNs = 0L;
        long importHistoriesStartNs = 0L;
        long importPartsStartNs = 0L;
        long importSystemConfigsStartNs = 0L;
        long importLiveMsgsStartNs = 0L;
        int importedUserCount = 0;
        int importedRoomCount = 0;
        int importedHistoryCount = 0;
        int importedPartCount = 0;
        int importedStorageRootCount = 0;
        int importedFileLocationCount = 0;
        int importedSystemConfigCount = 0;
        int importedLiveMsgCount = 0;
        int skippedPartCount = 0;
        int skippedLiveMsgCount = 0;

        // 流式解析：使用 JSONReader 逐 section 读取，避免将整个文件加载到内存
        startConfigTask("import", "导入配置", Math.max(1L, fileSize));
        try (InputStream is = new BufferedInputStream(file.getInputStream(), 65536);
             JSONReader reader = new JSONReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            reader.startObject();

            Map<Long, Long> userIdConverMap = new HashMap<>();
            Map<Long, Long> historyIdConverMap = new HashMap<>();
            Map<Long, Long> partIdConverMap = new HashMap<>();
            Map<Long, Long> storageRootIdConverMap = new HashMap<>();

            // 兼容旧导出文件：roomList 可能在 userList 之前（新导出已修复为 userList 优先）
            List<RecordRoom> pendingRooms = null;
            List<RecordHistoryPart> pendingParts = null;

            long importProcessed = 0L;

            while (reader.hasNext()) {
                String key = reader.readString();
                switch (key) {
                    case "storageRootList" -> {
                        importedStorageRootCount = importStorageRootSection(reader, storageRootIdConverMap);
                        importProcessed += importedStorageRootCount;
                    }
                    case "userList" -> {
                        importUsersStartNs = System.nanoTime();
                        updateConfigTask("导入用户", "正在导入用户配置", importProcessed);
                        importedUserCount = importUserSection(reader, userIdConverMap);
                        importProcessed += importedUserCount;
                        updateConfigTask("导入用户", "用户 " + importedUserCount + " 条", importProcessed);
                        log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Users.Success")
                                .add("count", importedUserCount));
                        if (pendingRooms != null) {
                            importRoomsStartNs = System.nanoTime();
                            updateConfigTask("导入房间", "正在导入房间配置", importProcessed);
                            importedRoomCount = importRoomBatch(pendingRooms, userIdConverMap);
                            importProcessed += importedRoomCount;
                            pendingRooms = null;
                            updateConfigTask("导入房间", "房间 " + importedRoomCount + " 条", importProcessed);
                            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Rooms.Success")
                                    .add("count", importedRoomCount));
                        }
                    }
                    case "roomList" -> {
                        importRoomsStartNs = System.nanoTime();
                        updateConfigTask("导入房间", "正在导入房间配置", importProcessed);
                        if (userIdConverMap.isEmpty()) {
                            pendingRooms = readRoomSection(reader);
                        } else {
                            importedRoomCount = importRoomSection(reader, userIdConverMap);
                            importProcessed += importedRoomCount;
                            updateConfigTask("导入房间", "房间 " + importedRoomCount + " 条", importProcessed);
                            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Rooms.Success")
                                    .add("count", importedRoomCount));
                        }
                    }
                    case "historyList" -> {
                        importHistoriesStartNs = System.nanoTime();
                        updateConfigTask("导入历史", "正在导入录制历史", importProcessed);
                        importedHistoryCount = importHistorySection(reader, historyIdConverMap);
                        importProcessed += importedHistoryCount;
                        updateConfigTask("导入历史", "历史 " + importedHistoryCount + " 条", importProcessed);
                        log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Histories.Success")
                                .add("count", importedHistoryCount));
                        if (pendingParts != null) {
                            importPartsStartNs = System.nanoTime();
                            updateConfigTask("导入分P", "正在导入分P记录", importProcessed);
                            int[] pr = importPartBatch(pendingParts, historyIdConverMap, partIdConverMap);
                            importedPartCount = pr[0];
                            skippedPartCount = pr[1];
                            importProcessed += pr[2];
                            pendingParts = null;
                            updateConfigTask("导入分P", "分P " + importedPartCount + " 条，跳过 " + skippedPartCount + " 条", importProcessed);
                            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Parts.Success")
                                    .add("count", importedPartCount));
                        }
                    }
                    case "partList" -> {
                        importPartsStartNs = System.nanoTime();
                        updateConfigTask("导入分P", "正在导入分P记录", importProcessed);
                        if (historyIdConverMap.isEmpty()) {
                            pendingParts = readPartSection(reader);
                        } else {
                            int[] pr = importPartSection(reader, historyIdConverMap, partIdConverMap);
                            importedPartCount = pr[0];
                            skippedPartCount = pr[1];
                            importProcessed += pr[2];
                            updateConfigTask("导入分P", "分P " + importedPartCount + " 条，跳过 " + skippedPartCount + " 条", importProcessed);
                            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Parts.Success")
                                    .add("count", importedPartCount));
                        }
                    }
                    case "partFileLocationList" -> {
                        if (partIdConverMap.isEmpty()) {
                            throw new IllegalArgumentException("partFileLocationList 必须位于 partList 之后");
                        }
                        importedFileLocationCount = importPartFileLocationSection(
                                reader, partIdConverMap, storageRootIdConverMap);
                        importProcessed += importedFileLocationCount;
                    }
                    case "systemConfigList" -> {
                        importSystemConfigsStartNs = System.nanoTime();
                        updateConfigTask("导入系统配置", "正在导入系统配置", importProcessed);
                        importedSystemConfigCount = importSystemConfigSection(reader);
                        importProcessed += importedSystemConfigCount;
                        updateConfigTask("导入系统配置", "系统配置 " + importedSystemConfigCount + " 条", importProcessed);
                        log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.SystemConfigs.Success")
                                .add("count", importedSystemConfigCount));
                    }
                    case "liveMsgList" -> {
                        importLiveMsgsStartNs = System.nanoTime();
                        updateConfigTask("导入弹幕", "正在导入弹幕数据", importProcessed);
                        int[] mr = importLiveMsgSection(reader, partIdConverMap, importProcessed);
                        importedLiveMsgCount = mr[0];
                        skippedLiveMsgCount = mr[1];
                        importProcessed += mr[2];
                        updateConfigTask("导入弹幕", "弹幕 " + importedLiveMsgCount + " 条，跳过 " + skippedLiveMsgCount + " 条", importProcessed);
                        log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.LiveMsgs.Success")
                                .add("count", importedLiveMsgCount)
                                .add("skipped", skippedLiveMsgCount));
                    }
                    default -> reader.readObject();
                }
            }
            // 兜底处理：如果只导入了 room/part 而没有先导 user/history
            // （例如导入文件只有 roomList 没有 userList），缓存的数据会在这里落库
            if (pendingRooms != null) {
                importRoomsStartNs = System.nanoTime();
                updateConfigTask("导入房间", "正在导入房间配置", importProcessed);
                importedRoomCount = importRoomBatch(pendingRooms, userIdConverMap);
                importProcessed += importedRoomCount;
                pendingRooms = null;
                updateConfigTask("导入房间", "房间 " + importedRoomCount + " 条", importProcessed);
                log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Rooms.Success")
                        .add("count", importedRoomCount));
            }
            if (pendingParts != null) {
                importPartsStartNs = System.nanoTime();
                updateConfigTask("导入分P", "正在导入分P记录", importProcessed);
                int[] pr = importPartBatch(pendingParts, historyIdConverMap, partIdConverMap);
                importedPartCount = pr[0];
                skippedPartCount = pr[1];
                importProcessed += pr[2];
                pendingParts = null;
                updateConfigTask("导入分P", "分P " + importedPartCount + " 条，跳过 " + skippedPartCount + " 条", importProcessed);
                log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Parts.Success")
                        .add("count", importedPartCount));
            }
            for (Long importedPartId : partIdConverMap.values()) {
                partRepository.findById(importedPartId).ifPresent(storageLifecycleMigrationService::migratePart);
            }
            reader.endObject();

            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Done")
                    .addRoundCount("importedUser", importedUserCount)
                    .addRoundCount("importedRoom", importedRoomCount)
                    .addRoundCount("importedHistory", importedHistoryCount)
                    .addRoundCount("importedPart", importedPartCount)
                    .addRoundCount("importedStorageRoot", importedStorageRootCount)
                    .addRoundCount("importedFileLocation", importedFileLocationCount)
                    .addRoundCount("importedSystemConfig", importedSystemConfigCount)
                    .addRoundCount("importedLiveMsg", importedLiveMsgCount)
                    .addRoundCount("skippedPart", skippedPartCount)
                    .addRoundCount("skippedLiveMsg", skippedLiveMsgCount)
                    .addStageCostMs("importUsers", importUsersStartNs)
                    .addStageCostMs("importRooms", importRoomsStartNs)
                    .addStageCostMs("importHistories", importHistoriesStartNs)
                    .addStageCostMs("importParts", importPartsStartNs)
                    .addStageCostMs("importSystemConfigs", importSystemConfigsStartNs)
                    .addStageCostMs("importLiveMsgs", importLiveMsgsStartNs)
                    .addStageCostMs("total", totalStartNs));
            finishConfigTask("导入完成");
        } catch (RuntimeException e) {
            failConfigTask("导入失败：" + e.getMessage());
            throw e;
        }
    }

    // ========== 流式导入 section 处理方法 ==========

    /** 流式导入用户：逐条读取、去重、分批 saveAll，构建旧ID→新ID映射 */
    private int importUserSection(JSONReader reader, Map<Long, Long> userIdConverMap) {
        reader.startArray();
        List<BiliBiliUser> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        List<Long> batchOldIds = new ArrayList<>(IMPORT_BATCH_SIZE);
        int count = 0;
        while (reader.hasNext()) {
            BiliBiliUser user = reader.readObject(BiliBiliUser.class);
            if (user.getUid() == null) continue;
            Long oldId = user.getId();
            user.setId(null);
            BiliBiliUser dbUser = userRepository.findByUid(user.getUid());
            if (dbUser != null) {
                user.setId(dbUser.getId());
            }
            batch.add(user);
            batchOldIds.add(oldId);
            count++;
            if (batch.size() >= IMPORT_BATCH_SIZE) {
                userRepository.saveAll(batch);
                for (int i = 0; i < batch.size(); i++) {
                    Long oid = batchOldIds.get(i);
                    if (oid != null) userIdConverMap.put(oid, batch.get(i).getId());
                }
                batch.clear();
                batchOldIds.clear();
            }
        }
        if (!batch.isEmpty()) {
            userRepository.saveAll(batch);
            for (int i = 0; i < batch.size(); i++) {
                Long oid = batchOldIds.get(i);
                if (oid != null) userIdConverMap.put(oid, batch.get(i).getId());
            }
        }
        reader.endArray();
        return count;
    }

    /** 流式导入房间：逐条读取、去重、uploadUserId 映射、分批 saveAll */
    private int importRoomSection(JSONReader reader, Map<Long, Long> userIdConverMap) {
        reader.startArray();
        List<RecordRoom> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        int count = 0;
        while (reader.hasNext()) {
            RecordRoom room = reader.readObject(RecordRoom.class);
            if (StringUtils.isBlank(room.getRoomId())) continue;
            importOneRoom(room, userIdConverMap);
            batch.add(room);
            count++;
            if (batch.size() >= IMPORT_BATCH_SIZE) {
                roomRepository.saveAll(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) roomRepository.saveAll(batch);
        reader.endArray();
        return count;
    }

    /** 处理单个房间的导入逻辑（去重、uploadUserId 映射、排序、规范化） */
    private void importOneRoom(RecordRoom room, Map<Long, Long> userIdConverMap) {
        Long oldUploadUserId = room.getUploadUserId();
        room.setId(null);
        RecordRoom dbRoom = roomRepository.findByRoomId(room.getRoomId());
        if (dbRoom != null) {
            room.setId(dbRoom.getId());
            if (oldUploadUserId == null || !userIdConverMap.containsKey(oldUploadUserId)) {
                room.setUploadUserId(dbRoom.getUploadUserId());
            }
        } else if (oldUploadUserId != null && userIdConverMap.containsKey(oldUploadUserId)) {
            room.setUploadUserId(userIdConverMap.get(oldUploadUserId));
        } else if (oldUploadUserId != null && userRepository.findById(oldUploadUserId).isPresent()) {
            room.setUploadUserId(oldUploadUserId);
        } else {
            room.setUploadUserId(null);
        }
        if (oldUploadUserId != null && userIdConverMap.containsKey(oldUploadUserId)) {
            room.setUploadUserId(userIdConverMap.get(oldUploadUserId));
        }
        if (room.getSortOrder() == null) {
            room.setSortOrder(nextSortOrder());
        }
        normalizeGiftReplySettings(room);
        if (StringUtils.isNotBlank(room.getMoveDir())) {
            storageRootService.getOrCreateArchiveRoot(room.getMoveDir());
        }
    }

    /** 批量导入已缓存的房间列表（兼容旧导出文件） */
    private int importRoomBatch(List<RecordRoom> rooms, Map<Long, Long> userIdConverMap) {
        int count = 0;
        for (RecordRoom room : rooms) {
            if (StringUtils.isBlank(room.getRoomId())) continue;
            importOneRoom(room, userIdConverMap);
            roomRepository.save(room);
            count++;
        }
        return count;
    }

    /** 读取房间列表到内存（兼容旧导出文件中 roomList 在 userList 之前的情况） */
    private List<RecordRoom> readRoomSection(JSONReader reader) {
        reader.startArray();
        List<RecordRoom> rooms = new ArrayList<>();
        while (reader.hasNext()) {
            rooms.add(reader.readObject(RecordRoom.class));
        }
        reader.endArray();
        return rooms;
    }

    /** 流式导入录制历史：逐条读取、去重、分批 saveAll，构建旧ID→新ID映射 */
    private int importHistorySection(JSONReader reader, Map<Long, Long> historyIdConverMap) {
        reader.startArray();
        List<RecordHistory> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        List<Long> batchOldIds = new ArrayList<>(IMPORT_BATCH_SIZE);
        int count = 0;
        while (reader.hasNext()) {
            RecordHistory history = reader.readObject(RecordHistory.class);
            Long oldId = history.getId();
            history.setId(null);
            RecordHistory dbHistory = historyRepository.findBySessionId(history.getSessionId());
            if (dbHistory != null) {
                history.setId(dbHistory.getId());
            }
            batch.add(history);
            batchOldIds.add(oldId);
            count++;
            if (batch.size() >= IMPORT_BATCH_SIZE) {
                historyRepository.saveAll(batch);
                for (int i = 0; i < batch.size(); i++) {
                    Long oid = batchOldIds.get(i);
                    if (oid != null) historyIdConverMap.put(oid, batch.get(i).getId());
                }
                batch.clear();
                batchOldIds.clear();
            }
        }
        if (!batch.isEmpty()) {
            historyRepository.saveAll(batch);
            for (int i = 0; i < batch.size(); i++) {
                Long oid = batchOldIds.get(i);
                if (oid != null) historyIdConverMap.put(oid, batch.get(i).getId());
            }
        }
        reader.endArray();
        return count;
    }

    /** 流式导入分P记录：逐条读取、去重、historyId 映射、分批 saveAll，构建旧ID→新ID映射。
     *  @return [importedCount, skippedCount, totalCount] */
    private int[] importPartSection(JSONReader reader, Map<Long, Long> historyIdConverMap,
                                     Map<Long, Long> partIdConverMap) {
        reader.startArray();
        List<RecordHistoryPart> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        List<Long> batchOldIds = new ArrayList<>(IMPORT_BATCH_SIZE);
        int imported = 0, skipped = 0;
        while (reader.hasNext()) {
            RecordHistoryPart part = reader.readObject(RecordHistoryPart.class);
            Long oldId = part.getId();
            Long oldHistoryId = part.getHistoryId();
            part.setId(null);
            RecordHistoryPart dbPart = StringUtils.isNotBlank(part.getFilePath())
                    ? partRepository.findByFilePath(part.getFilePath()) : null;
            if (dbPart != null) {
                part.setId(dbPart.getId());
            }
            if (oldHistoryId != null && historyIdConverMap.containsKey(oldHistoryId)) {
                part.setHistoryId(historyIdConverMap.get(oldHistoryId));
            } else if (dbPart != null) {
                part.setHistoryId(dbPart.getHistoryId());
            } else {
                skipped++;
                continue;
            }
            batch.add(part);
            batchOldIds.add(oldId);
            imported++;
            if (batch.size() >= IMPORT_BATCH_SIZE) {
                partRepository.saveAll(batch);
                for (int i = 0; i < batch.size(); i++) {
                    Long oid = batchOldIds.get(i);
                    if (oid != null) partIdConverMap.put(oid, batch.get(i).getId());
                }
                batch.clear();
                batchOldIds.clear();
            }
        }
        if (!batch.isEmpty()) {
            partRepository.saveAll(batch);
            for (int i = 0; i < batch.size(); i++) {
                Long oid = batchOldIds.get(i);
                if (oid != null) partIdConverMap.put(oid, batch.get(i).getId());
            }
        }
        reader.endArray();
        return new int[]{imported, skipped, imported + skipped};
    }

    /** 批量导入已缓存的分P列表（兼容旧导出文件） */
    private int[] importPartBatch(List<RecordHistoryPart> parts, Map<Long, Long> historyIdConverMap,
                                   Map<Long, Long> partIdConverMap) {
        int imported = 0, skipped = 0;
        for (RecordHistoryPart part : parts) {
            Long oldId = part.getId();
            Long oldHistoryId = part.getHistoryId();
            part.setId(null);
            RecordHistoryPart dbPart = StringUtils.isNotBlank(part.getFilePath())
                    ? partRepository.findByFilePath(part.getFilePath()) : null;
            if (dbPart != null) {
                part.setId(dbPart.getId());
            }
            if (oldHistoryId != null && historyIdConverMap.containsKey(oldHistoryId)) {
                part.setHistoryId(historyIdConverMap.get(oldHistoryId));
            } else if (dbPart != null) {
                part.setHistoryId(dbPart.getHistoryId());
            } else {
                skipped++;
                continue;
            }
            partRepository.save(part);
            if (oldId != null) partIdConverMap.put(oldId, part.getId());
            imported++;
        }
        return new int[]{imported, skipped, imported + skipped};
    }

    /** 读取分P列表到内存（兼容旧导出文件中 partList 在 historyList 之前的情况） */
    private List<RecordHistoryPart> readPartSection(JSONReader reader) {
        reader.startArray();
        List<RecordHistoryPart> parts = new ArrayList<>();
        while (reader.hasNext()) {
            parts.add(reader.readObject(RecordHistoryPart.class));
        }
        reader.endArray();
        return parts;
    }

    /** 流式导入系统配置：逐条读取、跳过无效、逐条写入 */
    private int importSystemConfigSection(JSONReader reader) {
        reader.startArray();
        int count = 0;
        while (reader.hasNext()) {
            SystemConfig systemConfig = reader.readObject(SystemConfig.class);
            if (StringUtils.isBlank(systemConfig.getConfigKey()) || systemConfig.getConfigValue() == null) {
                continue;
            }
            systemConfigService.updateConfig(systemConfig.getConfigKey(), systemConfig.getConfigValue());
            count++;
        }
        reader.endArray();
        return count;
    }

    /** 流式导入弹幕：先清除已映射分P的历史弹幕，再逐条读取、partId 映射、分批 saveAll。
     *  @return [importedCount, skippedCount, totalCount] */
    private int[] importLiveMsgSection(JSONReader reader, Map<Long, Long> partIdConverMap, long baseProcessed) {
        // 先清除已映射分P的历史弹幕
        Set<Long> mappedPartIds = new HashSet<>(partIdConverMap.values());
        for (Long partId : mappedPartIds) {
            liveMsgRepository.deleteByPartId(partId);
        }
        reader.startArray();
        List<LiveMsg> batch = new ArrayList<>(IMPORT_BATCH_SIZE);
        int imported = 0, skipped = 0;
        long processed = baseProcessed;
        while (reader.hasNext()) {
            LiveMsg liveMsg = reader.readObject(LiveMsg.class);
            Long newPartId = partIdConverMap.get(liveMsg.getPartId());
            if (newPartId == null) {
                skipped++;
                continue;
            }
            liveMsg.setId(null);
            liveMsg.setPartId(newPartId);
            batch.add(liveMsg);
            imported++;
            processed++;
            if (batch.size() >= IMPORT_BATCH_SIZE) {
                liveMsgRepository.saveAll(batch);
                batch.clear();
            }
            if (processed % 5000 == 0) {
                updateConfigTask("导入弹幕", "已导入 " + imported + " 条弹幕", processed);
            }
        }
        if (!batch.isEmpty()) liveMsgRepository.saveAll(batch);
        reader.endArray();
        return new int[]{imported, skipped, imported + skipped};
    }

    private int importStorageRootSection(JSONReader reader, Map<Long, Long> rootIdMap) {
        reader.startArray();
        int imported = 0;
        while (reader.hasNext()) {
            StorageRoot incoming = reader.readObject(StorageRoot.class);
            Long oldId = incoming.getId();
            StorageRoot target = StringUtils.isBlank(incoming.getRootKey()) ? null
                    : storageRootRepository.findByRootKey(incoming.getRootKey()).orElse(null);
            if (target == null) {
                incoming.setId(null);
                incoming.setStatus(StorageRoot.RootStatus.OFFLINE);
                incoming.setWritable(false);
                incoming.setActiveForNewFiles(false);
                incoming.setLastCheckedAt(null);
                target = storageRootRepository.save(incoming);
                imported++;
            }
            if (oldId != null) rootIdMap.put(oldId, target.getId());
        }
        reader.endArray();
        return imported;
    }

    private int importPartFileLocationSection(JSONReader reader, Map<Long, Long> partIdMap,
                                              Map<Long, Long> rootIdMap) {
        reader.startArray();
        int imported = 0;
        while (reader.hasNext()) {
            PartFileLocation location = reader.readObject(PartFileLocation.class);
            Long partId = partIdMap.get(location.getPartId());
            Long rootId = location.getStorageRootId() == null ? null : rootIdMap.get(location.getStorageRootId());
            if (partId == null || (location.getStorageRootId() != null && rootId == null)) continue;
            Path relative;
            try {
                relative = Paths.get(location.getRelativePath()).normalize();
            } catch (Exception e) {
                continue;
            }
            if (relative.isAbsolute() || relative.startsWith("..")
                    || location.getState() == PartFileLocation.LocationState.PROCESSING) {
                continue;
            }
            location.setId(null);
            location.setPartId(partId);
            location.setStorageRootId(rootId);
            location.setRelativePath(relative.toString().replace('\\', '/'));
            if (location.getRole() == PartFileLocation.LocationRole.PRIMARY) {
                for (PartFileLocation current : partFileLocationRepository.findByPartIdOrderByIdAsc(partId)) {
                    if (current.getRole() == PartFileLocation.LocationRole.PRIMARY) {
                        current.setRole(PartFileLocation.LocationRole.REPLICA);
                        partFileLocationRepository.save(current);
                    }
                }
            }
            if (partFileLocationRepository.findByPartIdAndStorageRootIdAndRelativePath(
                    partId, rootId, location.getRelativePath()).isPresent()) continue;
            partFileLocationRepository.save(location);
            imported++;
        }
        reader.endArray();
        return imported;
    }

    private Integer nextSortOrder() {
        Integer maxSortOrder = roomRepository.findMaxSortOrder();
        return (maxSortOrder == null ? 0 : maxSortOrder) + 1;
    }

    @PostMapping("/update")
    public boolean update(@RequestBody RecordRoom room) {
        Optional<RecordRoom> roomOptional = roomRepository.findById(room.getId());
        if (roomOptional.isPresent()) {
            RecordRoom dbRoom = roomOptional.get();
            dbRoom.setTid(room.getTid());
            dbRoom.setTags(room.getTags());
            dbRoom.setUpload(room.isUpload());
            dbRoom.setUploadUserId(room.getUploadUserId());
            SeasonSectionFixResult seasonSectionFixResult = validateAndFixSeasonSection(room.getSeasonId(), room.getSectionId(), dbRoom);
            dbRoom.setSeasonId(seasonSectionFixResult.seasonId());
            dbRoom.setSectionId(seasonSectionFixResult.sectionId());
            dbRoom.setHighEnergyCut(room.isHighEnergyCut());
            dbRoom.setPercentileRank(room.getPercentileRank());
            dbRoom.setIsOnlySelf(room.getIsOnlySelf());
            dbRoom.setNoDisturbance(room.getNoDisturbance());
            dbRoom.setTitleTemplate(room.getTitleTemplate());
            dbRoom.setPartTitleTemplate(room.getPartTitleTemplate());
            dbRoom.setDescTemplate(room.getDescTemplate());
            dbRoom.setDynamicTemplate(room.getDynamicTemplate());
            dbRoom.setCopyright(room.getCopyright());
            dbRoom.setLine(room.getLine());
            dbRoom.setCoverUrl(room.getCoverUrl());
            dbRoom.setWxuid(room.getWxuid());
            dbRoom.setServerChanSendKey(room.getServerChanSendKey());
            dbRoom.setServerChanChannel(room.getServerChanChannel());
            dbRoom.setPushMsgTags(PushNotifyClient.normalizePushMsgTags(room.getPushMsgTags()));
            dbRoom.setFileSizeLimit(room.getFileSizeLimit());
            dbRoom.setDurationLimit(room.getDurationLimit());
            dbRoom.setDeleteType(room.getDeleteType());
            dbRoom.setDeleteDay(room.getDeleteDay());
            dbRoom.setMoveDir(room.getMoveDir());
            dbRoom.setSendDm(room.getSendDm());
            dbRoom.setSendSc(room.getSendSc());
            dbRoom.setSendGiftReply(Boolean.TRUE.equals(room.getSendGiftReply()));
            dbRoom.setGiftReplyMinPriceCny(normalizeGiftReplyMinPrice(room.getGiftReplyMinPriceCny()));
            roomRepository.save(dbRoom);
            if (StringUtils.isNotBlank(dbRoom.getMoveDir())) {
                storageRootService.getOrCreateArchiveRoot(dbRoom.getMoveDir());
            }
            log.info("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Fixed")
                    .add("roomId", dbRoom.getRoomId())
                    .add("id", dbRoom.getId())
                    .add("uploadUserId", dbRoom.getUploadUserId())
                    .add("seasonId", dbRoom.getSeasonId())
                    .add("sectionId", dbRoom.getSectionId())
                    .add("action", seasonSectionFixResult.action()));
            return true;
        }
        return false;
    }

    private SeasonSectionFixResult validateAndFixSeasonSection(Long inputSeasonId, Long inputSectionId, RecordRoom room) {
        Long seasonId = normalizePositive(inputSeasonId);
        Long sectionId = normalizePositive(inputSectionId);
        if (seasonId == null) {
            return new SeasonSectionFixResult(null, null, "disable_no_season");
        }
        Long uploadUserId = room.getUploadUserId();
        if (uploadUserId == null) {
            log.warn("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Disabled")
                    .add("roomId", room.getRoomId())
                    .add("id", room.getId())
                    .add("seasonId", seasonId)
                    .add("sectionId", sectionId)
                    .add("reason", "upload_user_missing"));
            return new SeasonSectionFixResult(null, null, "disable_upload_user_missing");
        }
        Optional<BiliBiliUser> userOptional = userRepository.findById(uploadUserId);
        if (!userOptional.isPresent()) {
            log.warn("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Disabled")
                    .add("roomId", room.getRoomId())
                    .add("id", room.getId())
                    .add("seasonId", seasonId)
                    .add("sectionId", sectionId)
                    .add("uploadUserId", uploadUserId)
                    .add("reason", "upload_user_not_found"));
            return new SeasonSectionFixResult(null, null, "disable_upload_user_not_found");
        }
        String raw = BiliApi.getSeasons(userOptional.get());
        if (StringUtils.isBlank(raw)) {
            log.warn("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Disabled")
                    .add("roomId", room.getRoomId())
                    .add("id", room.getId())
                    .add("seasonId", seasonId)
                    .add("sectionId", sectionId)
                    .add("uploadUserId", uploadUserId)
                    .add("reason", "seasons_empty"));
            return new SeasonSectionFixResult(null, null, "disable_seasons_empty");
        }
        try {
            List<Map<String, Object>> seasons = JsonPath.read(raw, "$.data.seasons");
            for (Map<String, Object> item : seasons) {
                Object seasonObj = item.get("season");
                if (!(seasonObj instanceof Map<?, ?> seasonMap)) {
                    continue;
                }
                Long currentSeasonId = normalizePositive(asLong(seasonMap.get("id")));
                if (!Objects.equals(currentSeasonId, seasonId)) {
                    continue;
                }
                List<Long> sectionIds = extractSectionIds(item);
                Long firstSectionId = sectionIds.isEmpty() ? null : sectionIds.get(0);
                if (sectionId != null && sectionIds.contains(sectionId)) {
                    return new SeasonSectionFixResult(seasonId, sectionId, "keep");
                }
                if (firstSectionId != null) {
                    log.info("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Corrected")
                            .add("roomId", room.getRoomId())
                            .add("id", room.getId())
                            .add("seasonId", seasonId)
                            .add("oldSectionId", sectionId)
                            .add("newSectionId", firstSectionId)
                            .add("reason", "section_not_in_season"));
                    return new SeasonSectionFixResult(seasonId, firstSectionId, "use_first_section");
                }
                log.warn("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Disabled")
                        .add("roomId", room.getRoomId())
                        .add("id", room.getId())
                        .add("seasonId", seasonId)
                        .add("sectionId", sectionId)
                        .add("reason", "season_without_section"));
                return new SeasonSectionFixResult(null, null, "disable_no_section");
            }
            log.warn("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Disabled")
                    .add("roomId", room.getRoomId())
                    .add("id", room.getId())
                    .add("seasonId", seasonId)
                    .add("sectionId", sectionId)
                    .add("reason", "season_not_found"));
            return new SeasonSectionFixResult(null, null, "disable_season_not_found");
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Room.Update.SeasonSection.Disabled")
                    .add("roomId", room.getRoomId())
                    .add("id", room.getId())
                    .add("seasonId", seasonId)
                    .add("sectionId", sectionId)
                    .add("reason", "seasons_parse_error"), e);
            return new SeasonSectionFixResult(null, null, "disable_parse_error");
        }
    }

    private List<Long> extractSectionIds(Map<String, Object> item) {
        Object sectionsObj = item.get("sections");
        if (!(sectionsObj instanceof Map<?, ?> sectionsMap)) {
            return new ArrayList<>();
        }
        Object sectionListObj = sectionsMap.get("sections");
        if (!(sectionListObj instanceof List<?> sectionList)) {
            return new ArrayList<>();
        }
        List<Long> sectionIds = new ArrayList<>();
        for (Object sectionObj : sectionList) {
            if (!(sectionObj instanceof Map<?, ?> sectionMap)) {
                continue;
            }
            Long id = normalizePositive(asLong(sectionMap.get("id")));
            if (id != null) {
                sectionIds.add(id);
            }
        }
        return sectionIds;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignore) {
            return null;
        }
    }

    private Long normalizePositive(Long value) {
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    @PostMapping("/editLiveMsgSetting")
    public boolean editLiveMsgSetting(@RequestBody RecordRoom room) {
        Optional<RecordRoom> roomOptional = roomRepository.findById(room.getId());
        if (roomOptional.isPresent()) {
            RecordRoom dbRoom = roomOptional.get();
            dbRoom.setDmDistinct(room.getDmDistinct());
            dbRoom.setDmFanMedal(room.getDmFanMedal());
            dbRoom.setDmUlLevel(room.getDmUlLevel());
            dbRoom.setDmKeywordBlacklist(room.getDmKeywordBlacklist());
            roomRepository.save(dbRoom);
            return true;
        }
        return false;
    }

    @PostMapping("/add")
    public Map<String, String> add(@RequestBody RecordRoom add) {
        Map<String, String> result = new HashMap<>();
        if (StringUtils.isBlank(add.getRoomId())) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        RecordRoom room = roomRepository.findByRoomId(add.getRoomId());
        if (room != null) {
            result.put("type", "warning");
            result.put("msg", "房间号已存在");
            return result;
        } else {
            room = new RecordRoom();
            room.setRoomId(add.getRoomId());
            room.setSortOrder(nextSortOrder());
            roomRepository.save(room);
            result.put("type", "success");
            result.put("msg", "添加成功");
            return result;
        }
    }

    @PostMapping("/sort")
    public Map<String, Object> sort(@RequestBody List<Long> roomIds) {
        Map<String, Object> result = new HashMap<>();
        if (roomIds == null || roomIds.isEmpty()) {
            result.put("success", false);
            result.put("msg", "empty room order");
            return result;
        }

        Map<Long, RecordRoom> roomMap = new HashMap<>();
        for (RecordRoom room : roomRepository.findAllOrderBySortOrder()) {
            roomMap.put(room.getId(), room);
        }

        int order = 1;
        Set<Long> visited = new HashSet<>();
        List<RecordRoom> changedRooms = new ArrayList<>();
        for (Long roomId : roomIds) {
            RecordRoom room = roomMap.get(roomId);
            if (room == null || !visited.add(roomId)) {
                continue;
            }
            room.setSortOrder(order++);
            changedRooms.add(room);
        }
        for (RecordRoom room : roomRepository.findAllOrderBySortOrder()) {
            if (room.getId() != null && visited.add(room.getId())) {
                room.setSortOrder(order++);
                changedRooms.add(room);
            }
        }

        roomRepository.saveAll(changedRooms);
        result.put("success", true);
        result.put("count", changedRooms.size());
        return result;
    }

    private void normalizeGiftReplySettings(RecordRoom room) {
        if (room == null) {
            return;
        }
        room.setSendGiftReply(Boolean.TRUE.equals(room.getSendGiftReply()));
        room.setGiftReplyMinPriceCny(normalizeGiftReplyMinPrice(room.getGiftReplyMinPriceCny()));
    }

    private java.math.BigDecimal normalizeGiftReplyMinPrice(java.math.BigDecimal value) {
        if (value == null || value.compareTo(java.math.BigDecimal.ZERO) < 0) {
            return java.math.BigDecimal.ZERO;
        }
        java.math.BigDecimal normalized = value.setScale(0, java.math.RoundingMode.CEILING);
        java.math.BigDecimal maxSupported = new java.math.BigDecimal("99999999");
        if (normalized.compareTo(maxSupported) > 0) {
            return maxSupported;
        }
        return normalized;
    }

    @GetMapping("/delete/{roomId}")
    public Map<String, String> delete(@PathVariable("roomId") Long roomId) {
        Map<String, String> result = new HashMap<>();
        if (roomId == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        try {
            RoomDeletionService.DeletionResult deletion = roomDeletionService.delete(
                    roomId, RoomDeletionService.DeleteOptions.roomOnly());
            if (deletion.deleted()) {
                result.put("type", "success");
                result.put("msg", "房间删除成功");
                return result;
            } else {
                result.put("type", "warning");
                result.put("msg", "房间不存在");
                return result;
            }
        } catch (Exception e) {
            result.put("type", "error");
            result.put("msg", "房间删除失败==>" + e.getMessage());
            return result;
        }
    }

    @GetMapping("/{id}/deletion-preview")
    public Map<String, Object> deletionPreview(@PathVariable("id") Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        RoomDeletionService.DeletionPreview preview = roomDeletionService.preview(id);
        if (!preview.found()) {
            result.put("type", "warning");
            result.put("msg", "房间不存在");
            return result;
        }
        result.put("type", "success");
        result.put("msg", "删除影响范围加载成功");
        result.put("data", preview.toMap());
        return result;
    }

    @PostMapping("/{id}/delete")
    public Map<String, Object> deleteWithOptions(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) RoomDeletionRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        RoomDeletionRequest safeRequest = request == null ? new RoomDeletionRequest() : request;
        try {
            RoomDeletionService.DeletionResult deletion = roomDeletionService.delete(id,
                    new RoomDeletionService.DeleteOptions(
                            safeRequest.isDeleteHistories(),
                            safeRequest.isDeleteVideoFiles(),
                            safeRequest.isDeleteDanmakuFiles(),
                            safeRequest.isDeleteCoverFiles()));
            if (!deletion.found()) {
                result.put("type", "warning");
                result.put("msg", "房间不存在");
                return result;
            }
            result.put("data", deletion.toMap());
            if (deletion.notDeletedFiles().isEmpty()) {
                result.put("type", "success");
                result.put("msg", deletion.deletedHistoryCount() > 0
                        ? "房间及 " + deletion.deletedHistoryCount() + " 条录制历史删除成功"
                        : "房间删除成功");
            } else {
                result.put("type", "warning");
                result.put("msg", "房间和数据库记录已删除（有 "
                        + deletion.notDeletedFiles().size() + " 个本地文件未删除）");
            }
            return result;
        } catch (IllegalStateException e) {
            result.put("type", "warning");
            result.put("msg", e.getMessage());
            return result;
        } catch (IllegalArgumentException e) {
            result.put("type", "error");
            result.put("msg", e.getMessage());
            return result;
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("Room.Delete.Error")
                    .add("roomDatabaseId", id)
                    .add("err", e.getMessage()), e);
            result.put("type", "error");
            result.put("msg", "房间删除失败：" + e.getClass().getSimpleName());
            return result;
        }
    }

    @Data
    public static class RoomDeletionRequest {
        private boolean deleteHistories;
        private boolean deleteVideoFiles;
        private boolean deleteDanmakuFiles;
        private boolean deleteCoverFiles;
    }

    @PostMapping("/uploadCover")
    public Map<String, String> uploadCover(@RequestParam Long id, @RequestParam("file") MultipartFile file) {

        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }
        try {
            Optional<ImageDimensionsReader.Dimensions> image = ImageDimensionsReader.read(file.getInputStream());
            if (image.isEmpty()) {
                result.put("type", "warning");
                result.put("msg", "请上传图片文件");
                return result;
            }
            ImageDimensionsReader.Dimensions dimensions = image.get();
            if (dimensions.getWidth() < 1146 || dimensions.getHeight() < 717) {
                result.put("type", "warning");
                result.put("msg", "上传图片分辨率不低于1146*717,当前分辨率为"+dimensions.getWidth()+"*"+dimensions.getHeight());
                return result;
            }
        } catch (IOException e) {
            result.put("type", "warning");
            result.put("msg", "封面上传失败：" + e.getMessage());
            return result;
        }

        Optional<RecordRoom> roomOptional = roomRepository.findById(id);
        if (roomOptional.isPresent()) {
            try {
                RecordRoom room = roomOptional.get();
                Long userId = room.getUploadUserId();
                if (userId == null) {
                    result.put("type", "warning");
                    result.put("msg", "房间未绑定上传用户");
                    return result;
                }
                Optional<BiliBiliUser> userOptional = userRepository.findById(userId);
                if (!userOptional.isPresent()) {
                    result.put("type", "warning");
                    result.put("msg", "房间未绑定上传用户");
                    return result;
                }
                BiliBiliUser user = userOptional.get();
                byte[] bytes = file.getBytes();
                String response = BiliApi.uploadCover(user, file.getName(), bytes);
                String url = JsonPath.read(response, "data.url");
                if (StringUtils.isNotBlank(url)) {
                    room.setCoverUrl(url);
                    roomRepository.save(room);
                    result.put("type", "success");
                    result.put("coverUrl", url);
                    result.put("msg", "封面上传成功");
                    return result;
                }

            } catch (IOException e) {
                result.put("type", "warning");
                result.put("msg", "封面上传失败：" + e.getMessage());
                return result;
            }
            result.put("type", "warning");
            result.put("msg", "封面上传失败");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "房间不存在");
            return result;
        }
    }

    @GetMapping("/lines")
    public UploadEnums[] lines() {
        return UploadEnums.values();
    }

    @GetMapping("/test-lines")
    public Map<String, String> testLines() {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        try {
            // 1. 获取官方线路列表
            URL url = new URL("https://member.bilibili.com/preupload?r=ping&file=lines.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            if (conn.getResponseCode() == 200) {
                String jsonStr = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                List<Map<String, String>> officialLines = JSON.parseObject(jsonStr, new TypeReference<List<Map<String, String>>>(){});
                
                // 2. 准备并发测速
                // 使用足够的线程数以确保所有线路能同时开始测速，避免排队等待
                ExecutorService executor = Executors.newFixedThreadPool(UploadEnums.values().length);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Map<String, String> queryToUrl = new HashMap<>();
                
                for (Map<String, String> line : officialLines) {
                    queryToUrl.put(line.get("query"), line.get("url"));
                }

                // 3. 遍历本地枚举进行测速
                for (UploadEnums enumItem : UploadEnums.values()) {
                    // 去掉 lineQuery 开头的 ? 号来匹配
                    String queryKey = enumItem.getLineQuery().substring(1);
                    String testUrlStr = queryToUrl.get(queryKey);
                    
                    if (testUrlStr != null) {
                        if (testUrlStr.startsWith("//")) {
                            testUrlStr = "https:" + testUrlStr;
                        }
                        
                        String finalTestUrl = testUrlStr;
                        futures.add(CompletableFuture.runAsync(() -> {
                            long start = System.currentTimeMillis();
                            try {
                                URL testUrl = new URL(finalTestUrl);
                                HttpURLConnection testConn = (HttpURLConnection) testUrl.openConnection();
                                testConn.setRequestMethod("GET");
                                testConn.setConnectTimeout(3000);
                                testConn.setReadTimeout(3000);
                                
                                if (testConn.getResponseCode() == 200) {
                                    long cost = System.currentTimeMillis() - start;
                                    synchronized (result) {
                                        result.put(enumItem.getLine(), cost + "ms");
                                    }
                                } else {
                                    synchronized (result) {
                                        result.put(enumItem.getLine(), "Error " + testConn.getResponseCode());
                                    }
                                }
                            } catch (Exception e) {
                                synchronized (result) {
                                    result.put(enumItem.getLine(), "Timeout");
                                }
                            }
                        }, executor));
                    } else {
                        result.put(enumItem.getLine(), "Unknown");
                    }
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                executor.shutdown();
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("UploadLine.TestAll.Failed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", totalStartNs), e);
        }
        return result;
    }

    @GetMapping("/test-speed")
    public Map<String, Object> testSpeed(@RequestParam String line) {
        long totalStartNs = System.nanoTime();
        Map<String, Object> result = new HashMap<>();
        try {
            UploadEnums enumItem = UploadEnums.find(line);
            // 1. 获取官方线路列表
            URL url = new URL("https://member.bilibili.com/preupload?r=ping&file=lines.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                String jsonStr = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                List<Map<String, String>> officialLines = JSON.parseObject(jsonStr, new TypeReference<List<Map<String, String>>>() {});

                String queryKey = enumItem.getLineQuery().substring(1);
                String testUrlStr = null;
                for (Map<String, String> officialLine : officialLines) {
                    if (officialLine.get("query").equals(queryKey)) {
                        testUrlStr = officialLine.get("url");
                        break;
                    }
                }

                if (testUrlStr != null) {
                    if (testUrlStr.startsWith("//")) {
                        testUrlStr = "https:" + testUrlStr;
                    }
                    // 构造 1MB 数据
                    int size = 1 * 1024 * 1024;
                    byte[] data = new byte[size];
                    new Random().nextBytes(data);

                    long start = System.currentTimeMillis();
                    URL testUrl = new URL(testUrlStr + "?line=1"); // line=1 表示 1MB，参考官方 ping.js
                    HttpURLConnection testConn = (HttpURLConnection) testUrl.openConnection();
                    testConn.setRequestMethod("POST");
                    testConn.setDoOutput(true);
                    testConn.setConnectTimeout(5000);
                    testConn.setReadTimeout(10000); // 上传可能较慢，给多点时间

                    try (OutputStream os = testConn.getOutputStream()) {
                        os.write(data);
                        os.flush();
                    }

                    if (testConn.getResponseCode() == 200) {
                        long cost = System.currentTimeMillis() - start;
                        // 计算速度 MB/s
                        double speed = (double) size / 1024 / 1024 / ((double) cost / 1000);
                        result.put("speed", String.format("%.2f MB/s", speed));
                        result.put("cost", cost);
                        result.put("success", true);
                    } else {
                        result.put("success", false);
                        result.put("msg", "Error " + testConn.getResponseCode());
                    }
                } else {
                    result.put("success", false);
                    result.put("msg", "Unknown Line");
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "Timeout/Error");
            log.warn("[BLR] {}", LogKvs.event("UploadLine.TestSpeed.Failed")
                    .add("line", line)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", totalStartNs), e);
        }
        return result;
    }

    @GetMapping("/verification")
    public String verification(String template) {
        template = template.replace("${uname}", "主播名称")
                .replace("${title}", "直播标题")
                .replace("${roomId}", "房间号");
        if (template.contains("${")) {
            String date = template.substring(template.indexOf("${"), template.indexOf("}") + 1);
            String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern(date.substring(2, date.length() - 1)));
            template = template.replace(date, format);
        }


        return template;
    }

    @GetMapping("/seasons/{roomId}")
    public String seasons(@PathVariable("roomId") Long roomId) {
        long totalStartNs = System.nanoTime();
        Optional<RecordRoom> roomOptional = roomRepository.findById(roomId);
        if (roomOptional.isPresent()) {
            RecordRoom room = roomOptional.get();
            if (room.getUploadUserId() != null) {
                Optional<BiliBiliUser> biliUserOptional = userRepository.findById(room.getUploadUserId());
                if (!biliUserOptional.isPresent()) {
                    return "{\"code\":-1,\"message\":\"未找到上传用户\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
                }
                BiliBiliUser biliUser = biliUserOptional.get();
                int attempt = 0;
                while (true) {
                    try {
                        long apiCallStartNs = System.nanoTime();
                        String raw = BiliApi.getSeasons(biliUser);
                        if (StringUtils.isBlank(raw)) {
                            log.warn("[BLR] {}", LogKvs.event("Room.Seasons.FetchFailed")
                                    .add("roomId", roomId)
                                    .add("uploadUserId", room.getUploadUserId())
                                    .add("timeout", false)
                                    .add("attempt", attempt)
                                    .add("reason", "empty_response")
                                    .addStageCostMs("apiCall", apiCallStartNs)
                                    .addStageCostMs("total", totalStartNs));
                            return "{\"code\":-1,\"message\":\"获取合集失败\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
                        }

                        try {
                            Map<String, Object> obj = JSON.parseObject(raw, new TypeReference<Map<String, Object>>() {});
                            Object dataObj = obj.get("data");
                            Map<String, Object> data;
                            if (dataObj instanceof Map<?, ?> rawMap) {
                                data = new HashMap<>();
                                for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
                                    if (entry.getKey() instanceof String key) {
                                        data.put(key, entry.getValue());
                                    }
                                }
                            } else {
                                data = new HashMap<>();
                                obj.put("data", data);
                            }
                            Object seasons = data.get("seasons");
                            if (!(seasons instanceof List)) {
                                data.put("seasons", new ArrayList<>());
                            }
                            if (!obj.containsKey("ttl")) {
                                obj.put("ttl", 1);
                            }
                            log.info("[BLR] {}", LogKvs.event("Room.Seasons.Fetch.Success")
                                    .add("roomId", roomId)
                                    .add("uploadUserId", room.getUploadUserId())
                                    .addRoundCount("attempt", attempt)
                                    .add("respLen", raw.length())
                                    .addStageCostMs("apiCall", apiCallStartNs)
                                    .addStageCostMs("total", totalStartNs));
                            return JSON.toJSONString(obj);
                        } catch (Exception ignored) {
                            log.info("[BLR] {}", LogKvs.event("Room.Seasons.Fetch.Success")
                                    .add("roomId", roomId)
                                    .add("uploadUserId", room.getUploadUserId())
                                    .addRoundCount("attempt", attempt)
                                    .add("respLen", raw.length())
                                    .addStageCostMs("apiCall", apiCallStartNs)
                                    .addStageCostMs("total", totalStartNs));
                            return raw;
                        }
                    } catch (RuntimeException e) {
                        boolean timeout = StringUtils.containsIgnoreCase(e.getMessage(), "timed out");
                        if (timeout && attempt < 1) {
                            attempt++;
                            try { Thread.sleep(200L); } catch (Exception ignored) {}
                            continue;
                        }
                        log.warn("[BLR] {}", LogKvs.event("Room.Seasons.FetchFailed")
                                .add("roomId", roomId)
                                .add("uploadUserId", room.getUploadUserId())
                                .add("timeout", timeout)
                                .add("attempt", attempt)
                                .addIfNotBlank("err", e.getMessage())
                                .add("ex", e.getClass().getSimpleName())
                                .addStageCostMs("total", totalStartNs), e);
                        return "{\"code\":-1,\"message\":\"获取合集失败\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
                    }
                }
            }
        }
        // 前端 dataType=json 且会访问 data.data.seasons，这里返回一个最小可用结构，避免空指针
        return "{\"code\":-1,\"message\":\"未配置上传用户\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
    }

    @GetMapping(value = "/image-proxy")
    public ResponseEntity<byte[]> imageProxy(@RequestParam("url") String url,
                                             @RequestParam(value = "kind", required = false) String kind) {
        long totalStartNs = System.nanoTime();
        if (url == null || url.isBlank()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        boolean avatar = "avatar".equalsIgnoreCase(kind);
        try {
            CachedImage cached = imageCache.getIfPresent(url);
            if (cached != null) {
                log.debug("[BLR] Image proxy cache hit: {}", url);
                return buildImageResponse(cached);
            }

            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || (!host.endsWith(".hdslb.com") && !host.endsWith(".biliimg.com"))) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.InvalidHost")
                        .add("host", host)
                        .addStageCostMs("total", totalStartNs));
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.InvalidScheme")
                        .add("scheme", scheme)
                        .addStageCostMs("total", totalStartNs));
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            CompletableFuture<CachedImage> placeholder = new CompletableFuture<>();
            CompletableFuture<CachedImage> inFlight = imageInflight.putIfAbsent(url, placeholder);
            if (inFlight == null) {
                boolean acquired = false;
                Semaphore semaphore = avatar ? avatarProxySemaphore : imageProxySemaphore;
                try {
                    acquired = semaphore.tryAcquire(4, TimeUnit.SECONDS);
                    if (!acquired) {
                        placeholder.completeExceptionally(new RuntimeException("image_proxy_busy"));
                        log.warn("[BLR] {}", LogKvs.event("ImageProxy.Busy")
                                .add("url", url)
                                .addStageCostMs("total", totalStartNs));
                        return new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS);
                    }
                    
                    // 增加全局请求间隔控制：每次请求回源前，强制等待至少500ms
                    // 结合Semaphore=3，即使3个并发，也会被这个串行sleep拖慢节奏
                    if (!avatar) {
                        synchronized (lastRequestTime) {
                            long now = System.currentTimeMillis();
                            long last = lastRequestTime.get();
                            if (now - last < 600) {
                                try {
                                    Thread.sleep(600 - (now - last));
                                } catch (InterruptedException ignored) {}
                            }
                            lastRequestTime.set(System.currentTimeMillis());
                        }
                    }
                    CachedImage loaded = loadImageFromUpstream(url, uri);
                    placeholder.complete(loaded);
                    return buildImageResponse(loaded);
                } catch (Exception e) {
                    placeholder.completeExceptionally(e);
                    throw e;
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                    imageInflight.remove(url, placeholder);
                }
            }

            CachedImage shared = inFlight.get(8, TimeUnit.SECONDS);
            return buildImageResponse(shared);
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("ImageProxy.Failed")
                    .add("url", url)
                    .add("err", e.getMessage())
                    .addStageCostMs("total", totalStartNs));
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<byte[]> buildImageResponse(CachedImage cachedImage) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(cachedImage.getContentType());
        headers.setCacheControl("public, max-age=604800");
        return new ResponseEntity<>(cachedImage.getBytes(), headers, HttpStatus.OK);
    }

    private CachedImage loadImageFromUpstream(String url, URI uri) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        RestTemplate restTemplate = new RestTemplate(factory);

        HttpHeaders forwardHeaders = new HttpHeaders();
        forwardHeaders.add("Referer", "https://www.bilibili.com/");
        forwardHeaders.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        forwardHeaders.add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
        HttpEntity<Void> httpEntity = new HttpEntity<>(forwardHeaders);

        ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET, httpEntity, byte[].class);
        byte[] imageBytes = resp.getBody();
        MediaType ct = resp.getHeaders().getContentType();

        if (!resp.getStatusCode().is2xxSuccessful()) {
            log.warn("[BLR] {}", LogKvs.event("ImageProxy.UpstreamNon2xx")
                    .add("url", url)
                    .add("status", resp.getStatusCode().value()));
            throw new RuntimeException("upstream_status_" + resp.getStatusCode().value());
        }
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("[BLR] {}", LogKvs.event("ImageProxy.EmptyBody")
                    .add("url", url)
                    .add("contentType", ct == null ? "" : ct.toString()));
            throw new RuntimeException("upstream_empty_body");
        }
        if (ct != null && !"image".equalsIgnoreCase(ct.getType()) && !ct.toString().contains("octet-stream")) {
            log.warn("[BLR] {}", LogKvs.event("ImageProxy.NonImageContentType")
                    .add("url", url)
                    .add("contentType", ct.toString()));
        }

        if (ct == null) {
            String path = uri.getPath();
            ct = MediaTypeFactory.getMediaType(path).orElse(MediaType.IMAGE_JPEG);
        }
        CachedImage loaded = new CachedImage(imageBytes, ct);
        imageCache.put(url, loaded);
        return loaded;
    }
}
