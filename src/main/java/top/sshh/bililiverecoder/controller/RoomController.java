package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
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
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.PushNotifyClient;
import top.sshh.bililiverecoder.util.UploadEnums;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    private final Cache<String, CachedImage> imageCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(1, TimeUnit.DAYS)
            .build();
    private final Map<String, CompletableFuture<CachedImage>> imageInflight = new ConcurrentHashMap<>();
    private final Semaphore imageProxySemaphore = new Semaphore(3, true);
    // 记录上一次请求B站的时间戳
    private final AtomicLong lastRequestTime = new AtomicLong(0);

    @Data
    @AllArgsConstructor
    private static class CachedImage {
        private byte[] bytes;
        private MediaType contentType;
    }

    private record SeasonSectionFixResult(Long seasonId, Long sectionId, String action) {}


    @PostMapping
    public List<RecordRoom> list() {
        return roomRepository.findAllOrderBySortOrder();
    }


    @PostMapping("/exportConfig")
    public void exportConfig(@RequestBody ExportConfigParams params, HttpServletResponse response) throws IOException {
        Map<String,Object> map = new LinkedHashMap<>();
        if(params.isExportRoom()){
            List<RecordRoom> roomList = this.list();
            map.put("roomList",roomList);
        }
        if(params.isExportUser()){
            List<BiliBiliUser> userList = new ArrayList<>();
            Iterator<BiliBiliUser> userIterator = userRepository.findAll().iterator();
            userIterator.forEachRemaining(userList::add);
            map.put("userList",userList);
        }
        if(params.isExportHistory()){
            List<RecordHistory> historyList = new ArrayList<>();
            Iterator<RecordHistory> historyIterator = historyRepository.findAll().iterator();
            historyIterator.forEachRemaining(historyList::add);
            map.put("historyList",historyList);
            List<RecordHistoryPart> partList = new ArrayList<>();
            Iterator<RecordHistoryPart> partIterator = partRepository.findAll().iterator();
            partIterator.forEachRemaining(partList::add);
            map.put("partList",partList);
        }
        if(params.isExportSystemConfig()){
            map.put("systemConfigList", systemConfigRepository.findAll());
        }
        if(params.isExportLiveMsg()){
            List<LiveMsg> liveMsgList = new ArrayList<>();
            Iterator<LiveMsg> liveMsgIterator = liveMsgRepository.findAll().iterator();
            liveMsgIterator.forEachRemaining(liveMsgList::add);
            map.put("liveMsgList", liveMsgList);
        }
        String jsonString = JSON.toJSONString(map);
        String timeString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分"));
        // 构造响应头，指定文件名，并将文件名进行URL编码
        String encodedFilename = URLEncoder.encode("biliupForJavaConfig_"+timeString+".json", StandardCharsets.UTF_8).replaceAll("\\+", "%20");

        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename="+encodedFilename);
        // 将JSON字符串写入到响应输出流中
        OutputStream out = response.getOutputStream();
        out.write(jsonString.getBytes(StandardCharsets.UTF_8));
        out.flush();
        out.close();
    }

    @PostMapping("/uploadConfig")
    public void uploadConfig(@RequestParam("file") MultipartFile file) throws IOException {
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
        int importedSystemConfigCount = 0;
        int importedLiveMsgCount = 0;
        int skippedPartCount = 0;
        int skippedLiveMsgCount = 0;
        // 获取上传的文件内容
        byte[] bytes = file.getBytes();
        // 将文件内容转换为JSON字符串
        String json = new String(bytes, StandardCharsets.UTF_8);

        // 将JSON字符串转换为Map对象
        Map<String,Object> configMap = JSON.parseObject(json, new TypeReference<>() {
        });
        if (configMap == null) {
            throw new IOException("Invalid config json");
        }
        List<RecordRoom> roomList = parseConfigList(configMap, "roomList", new TypeReference<>() {});
        List<BiliBiliUser> userList = parseConfigList(configMap, "userList", new TypeReference<>() {});
        List<RecordHistory> historyList = parseConfigList(configMap, "historyList", new TypeReference<>() {});
        List<RecordHistoryPart> partList = parseConfigList(configMap, "partList", new TypeReference<>() {});
        List<SystemConfig> systemConfigList = parseConfigList(configMap, "systemConfigList", new TypeReference<>() {});
        List<LiveMsg> liveMsgList = parseConfigList(configMap, "liveMsgList", new TypeReference<>() {});


        Map<Long,Long> userIdConverMap = new HashMap<>();
        if(userList.size()>0){
            importUsersStartNs = System.nanoTime();
            for (BiliBiliUser user : userList) {
                if (user.getUid() == null) {
                    continue;
                }
                Long id = user.getId();
                user.setId(null);
                BiliBiliUser dbUser = userRepository.findByUid(user.getUid());
                if(dbUser != null){
                    user.setId(dbUser.getId());
                }
                userRepository.save(user);
                if (id != null) {
                    userIdConverMap.put(id,user.getId());
                }
                importedUserCount++;
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Users.Success")
                    .add("count", importedUserCount));
        }
        if(roomList.size()>0){
            importRoomsStartNs = System.nanoTime();
            for (RecordRoom room : roomList) {
                if (StringUtils.isBlank(room.getRoomId())) {
                    continue;
                }
                Long oldUploadUserId = room.getUploadUserId();
                room.setId(null);
                RecordRoom dbRoom = roomRepository.findByRoomId(room.getRoomId());
                if(dbRoom != null){
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
                roomRepository.save(room);
                importedRoomCount++;
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Rooms.Success")
                    .add("count", importedRoomCount));
        }
        Map<Long,Long> historyIdConverMap = new HashMap<>();
        if(historyList.size()>0){
            importHistoriesStartNs = System.nanoTime();
            for (RecordHistory history : historyList) {
                Long oldId = history.getId();
                history.setId(null);
                RecordHistory dbHistory = historyRepository.findBySessionId(history.getSessionId());
                if(dbHistory != null){
                    history.setId(dbHistory.getId());
                }
                historyRepository.save(history);
                if (oldId != null) {
                    historyIdConverMap.put(oldId,history.getId());
                }
                importedHistoryCount++;
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Histories.Success")
                    .add("count", importedHistoryCount));
        }
        Map<Long,Long> partIdConverMap = new HashMap<>();
        if(partList.size()>0){
            importPartsStartNs = System.nanoTime();
            for (RecordHistoryPart part : partList) {
                Long oldId = part.getId();
                Long oldHistoryId = part.getHistoryId();
                part.setId(null);
                RecordHistoryPart dbPart = StringUtils.isNotBlank(part.getFilePath())
                        ? partRepository.findByFilePath(part.getFilePath())
                        : null;
                if(dbPart != null){
                    part.setId(dbPart.getId());
                }
                if (oldHistoryId != null && historyIdConverMap.containsKey(oldHistoryId)) {
                    part.setHistoryId(historyIdConverMap.get(oldHistoryId));
                } else if (dbPart != null) {
                    part.setHistoryId(dbPart.getHistoryId());
                } else {
                    skippedPartCount++;
                    continue;
                }
                partRepository.save(part);
                if (oldId != null) {
                    partIdConverMap.put(oldId,part.getId());
                }
                importedPartCount++;
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Parts.Success")
                    .add("count", importedPartCount));
        }
        // 在控制台输出转换后的Map对象
        if(systemConfigList.size()>0){
            importSystemConfigsStartNs = System.nanoTime();
            for (SystemConfig systemConfig : systemConfigList) {
                if (StringUtils.isBlank(systemConfig.getConfigKey()) || systemConfig.getConfigValue() == null) {
                    continue;
                }
                systemConfigService.updateConfig(systemConfig.getConfigKey(), systemConfig.getConfigValue());
                importedSystemConfigCount++;
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.SystemConfigs.Success")
                    .add("count", importedSystemConfigCount));
        }
        if(liveMsgList.size()>0){
            importLiveMsgsStartNs = System.nanoTime();
            Set<Long> mappedPartIds = new HashSet<>(partIdConverMap.values());
            for (Long partId : mappedPartIds) {
                liveMsgRepository.deleteByPartId(partId);
            }
            for (LiveMsg liveMsg : liveMsgList) {
                Long newPartId = partIdConverMap.get(liveMsg.getPartId());
                if (newPartId == null) {
                    skippedLiveMsgCount++;
                    continue;
                }
                liveMsg.setId(null);
                liveMsg.setPartId(newPartId);
                liveMsgRepository.save(liveMsg);
                importedLiveMsgCount++;
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.LiveMsgs.Success")
                    .add("count", importedLiveMsgCount)
                    .add("skipped", skippedLiveMsgCount));
        }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Done")
                .addRoundCount("importedUser", importedUserCount)
                .addRoundCount("importedRoom", importedRoomCount)
                .addRoundCount("importedHistory", importedHistoryCount)
                .addRoundCount("importedPart", importedPartCount)
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
    }

    private <T> List<T> parseConfigList(Map<String,Object> configMap, String key, TypeReference<List<T>> typeReference) {
        Object value = configMap.get(key);
        if (value == null) {
            return Collections.emptyList();
        }
        List<T> list = JSON.parseObject(JSON.toJSONString(value), typeReference);
        return list == null ? Collections.emptyList() : list;
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
            roomRepository.save(dbRoom);
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

    @GetMapping("/delete/{roomId}")
    public Map<String, String> delete(@PathVariable("roomId") Long roomId) {
        Map<String, String> result = new HashMap<>();
        if (roomId == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        try {
            Optional<RecordRoom> roomOptional = roomRepository.findById(roomId);
            if (roomOptional.isPresent()) {
                roomRepository.delete(roomOptional.get());
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

    @PostMapping("/uploadCover")
    public Map<String, String> uploadCover(@RequestParam Long id, @RequestParam("file") MultipartFile file) {

        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                result.put("type", "warning");
                result.put("msg", "请上传图片文件");
                return result;
            }
            if (image.getWidth() < 1146 || image.getHeight() < 717) {
                result.put("type", "warning");
                result.put("msg", "上传图片分辨率不低于1146*717,当前分辨率为"+image.getWidth()+"*"+image.getHeight());
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
    public ResponseEntity<byte[]> imageProxy(@RequestParam("url") String url) {
        long totalStartNs = System.nanoTime();
        if (url == null || url.isBlank()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
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
                try {
                    acquired = imageProxySemaphore.tryAcquire(4, TimeUnit.SECONDS);
                    if (!acquired) {
                        placeholder.completeExceptionally(new RuntimeException("image_proxy_busy"));
                        log.warn("[BLR] {}", LogKvs.event("ImageProxy.Busy")
                                .add("url", url)
                                .addStageCostMs("total", totalStartNs));
                        return new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS);
                    }
                    
                    // 增加全局请求间隔控制：每次请求回源前，强制等待至少500ms
                    // 结合Semaphore=3，即使3个并发，也会被这个串行sleep拖慢节奏
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

                    CachedImage loaded = loadImageFromUpstream(url, uri);
                    placeholder.complete(loaded);
                    return buildImageResponse(loaded);
                } catch (Exception e) {
                    placeholder.completeExceptionally(e);
                    throw e;
                } finally {
                    if (acquired) {
                        imageProxySemaphore.release();
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
