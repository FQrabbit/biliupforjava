package top.sshh.bililiverecoder.controller;


import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.RoomLiveSessionStatsRepository;
import top.sshh.bililiverecoder.service.impl.HighEnergyCutPublishService;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.TaskUtil;
import top.sshh.bililiverecoder.util.UploadProgressTracker;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.service.UploadPauseService;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/history")
public class HistoryController {


    @Value("${record.work-path}")
    private String workPath;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private BiliUserRepository userRepository;
    @Autowired
    private RecordBiliPublishService publishService;
    @Autowired
    private LiveMsgRepository msgRepository;
    @Autowired
    private RoomLiveSessionStatsRepository sessionStatsRepository;
    @Autowired
    private LiveMsgService msgService;
    @Autowired
    private HighEnergyCutPublishService highEnergyCutPublishService;
    @Autowired
    private top.sshh.bililiverecoder.job.videoSyncJob videoSyncJob;
    @Autowired
    private SystemConfigService systemConfigService;
    @Autowired
    private UploadProgressTracker uploadProgressTracker;
    @Autowired
    private UploadPauseService uploadPauseService;
    @PersistenceContext
    private EntityManager entityManager;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @PostMapping("/list")
    public Map<String, Object> list(@RequestBody RecordHistoryDTO request) {
        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
        // 指定结果视图
        CriteriaQuery<RecordHistory> criteriaQuery = criteriaBuilder.createQuery(RecordHistory.class);
        // 查询基础表
        Root<RecordHistory> root = criteriaQuery.from(RecordHistory.class);
        criteriaQuery.select(root);
        //Predicate 过滤条件 构建where字句可能的各种条件
        //这里用List存放多种查询条件,实现动态查询
        List<Predicate> predicatesList = getPredicates(criteriaBuilder, root, request);

        //where()拼接查询条件
        if (predicatesList.size() > 0) {
            criteriaQuery.where(predicatesList.toArray(new Predicate[predicatesList.size()]));
        }
        criteriaQuery.orderBy(criteriaBuilder.desc(root.get("endTime")));
        
        // 先创建查询获取总数
        CriteriaQuery<Long> countCriteriaQuery = criteriaBuilder.createQuery(Long.class);
        Root<RecordHistory> countRoot = countCriteriaQuery.from(RecordHistory.class);
        countCriteriaQuery.select(criteriaBuilder.count(countRoot));
        List<Predicate> countPredicatesList = getPredicates(criteriaBuilder, countRoot, request);
        if (countPredicatesList.size() > 0) {
            countCriteriaQuery.where(countPredicatesList.toArray(new Predicate[countPredicatesList.size()]));
        }
        TypedQuery<Long> countQuery = entityManager.createQuery(countCriteriaQuery);
        int total = countQuery.getSingleResult().intValue();
        
        // 重新创建查询对象用于分页查询
        TypedQuery<RecordHistory> typedQuery = entityManager.createQuery(criteriaQuery);
        typedQuery.setFirstResult((request.getCurrent()-1)*request.getPageSize());
        typedQuery.setMaxResults(request.getPageSize());
        List<RecordHistory> list = typedQuery.getResultList();
        
        // 获取一次配置，避免在循环中重复查询数据库
        Map<String, String> configMap = systemConfigService.getAllConfigsMap();

        Map<String,String> roomCache = new HashMap<>();
        Map<String, RecordRoom> roomEntityCache = new HashMap<>();
        // 房间数量通常较少，直接全量加载比循环查询更高效
        Iterable<RecordRoom> iterable = roomRepository.findAll();
        for (RecordRoom recordRoom : iterable) {
            roomCache.put(recordRoom.getRoomId(),recordRoom.getUname());
            roomEntityCache.put(recordRoom.getRoomId(), recordRoom);
        }

        Map<Long, RoomLiveSessionStats> sessionStatsMap = buildPageSessionStats(list);
        Map<Long, PartListStats> partStatsMap = buildPagePartStats(list);
        Map<String, MsgListStats> msgStatsMap = buildPageMsgStats(list, sessionStatsMap);
        
        // 同步执行数据库查询操作，避免并行流中的 EntityManager 会话问题
        for (RecordHistory history : list) {
            history.setRoomName(roomCache.get(history.getRoomId()));
            // 使用统一方法填充额外字段（分P统计、放弃分P、弹幕统计等）
            populateHistoryFields(history, configMap, roomEntityCache.get(history.getRoomId()),
                    partStatsMap.get(history.getId()), msgStatsMap.get(history.getBvId()));
        }
        Map<String,Object> result = new HashMap<>();
        result.put("data",list);
        result.put("total",total);

        if (!Boolean.TRUE.equals(request.getSkipCategoryCounts())) {
            // 统计“工作中”和“已归档”的稿件总数。翻页请求会跳过这里，避免每页额外全局扫描。
            try {
                // 计算“工作中”的稿件数量
                CriteriaQuery<Long> workingQuery = criteriaBuilder.createQuery(Long.class);
                Root<RecordHistory> workingRoot = workingQuery.from(RecordHistory.class);
                workingQuery.select(criteriaBuilder.count(workingRoot));
                
                // 重新构建归档状态的判断条件（因为查询的主体对象变了，所以需要重建条件）
                Predicate isArchivedForWorking = buildFullArchivedPredicate(criteriaBuilder, workingRoot);
                workingQuery.where(criteriaBuilder.not(isArchivedForWorking));
                Long workingCount = entityManager.createQuery(workingQuery).getSingleResult();
                result.put("workingCount", workingCount);

                // 计算“已归档”的稿件数量
                CriteriaQuery<Long> archivedQuery = criteriaBuilder.createQuery(Long.class);
                Root<RecordHistory> archivedRoot = archivedQuery.from(RecordHistory.class);
                archivedQuery.select(criteriaBuilder.count(archivedRoot));
                
                // 重新构建归档状态的判断条件（用于已归档查询）
                Predicate isArchivedForArchived = buildFullArchivedPredicate(criteriaBuilder, archivedRoot);
                archivedQuery.where(isArchivedForArchived);
                Long archivedCount = entityManager.createQuery(archivedQuery).getSingleResult();
                result.put("archivedCount", archivedCount);

            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("History.Count.CalcFailed")
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
                result.put("workingCount", 0);
                result.put("archivedCount", 0);
            }
        }

        return result;
    }

    @PostMapping("/refreshStatus")
    public void refreshStatus(@RequestBody RecordHistoryDTO request) {
        Optional<RecordHistory> historyOptional = historyRepository.findById(request.getId());
        if (historyOptional.isPresent()) {
            videoSyncJob.syncStatusOnly(historyOptional.get());
        }
    }

    @GetMapping("/{id}/candidate-files")
    public Map<String, Object> candidateFiles(@PathVariable("id") Long id,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(required = false, defaultValue = "200") int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (id == null) {
            result.put("items", List.of());
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isEmpty()) {
            result.put("items", List.of());
            return result;
        }
        RecordHistory history = historyOptional.get();
        String kw = keyword == null ? null : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        int max = Math.max(1, Math.min(limit, 500));
        String[] allowedExt = new String[]{".flv", ".mp4", ".ts", ".mkv", ".mov", ".m4v"};

        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        LinkedHashSet<String> searchRoots = resolveCandidateFileSearchRoots(history);
        for (String root : searchRoots) {
            collectCandidateFiles(new File(root), kw, allowedExt, items, seenPaths);
        }

        items.sort((a, b) -> Long.compare(((Number) b.getOrDefault("lastModified", 0)).longValue(), ((Number) a.getOrDefault("lastModified", 0)).longValue()));
        if (items.size() > max) {
            items = items.subList(0, max);
        }

        result.put("items", items);
        return result;
    }

    @PostMapping("/{id}/upload/pause")
    public Map<String, Object> pauseUpload(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> request) {
        String reason = request == null ? null : String.valueOf(request.getOrDefault("reason", ""));
        return uploadPauseService.pauseHistory(id, reason);
    }

    @PostMapping("/{id}/upload/resume")
    public Map<String, Object> resumeUpload(@PathVariable("id") Long id) {
        return uploadPauseService.resumeHistory(id);
    }

    @GetMapping("/{id}/edit-parts/draft")
    public Map<String, Object> editPartsDraft(@PathVariable("id") Long id) {
        return publishService.buildEditPartsDraft(id);
    }

    @PostMapping("/{id}/edit-parts/local-upload")
    public Map<String, Object> uploadEditPartLocalFile(@PathVariable("id") Long id,
                                                       @RequestParam("sessionId") String sessionId,
                                                       @RequestParam("file") MultipartFile file) {
        return publishService.saveEditPartTempFile(id, sessionId, file);
    }

    @PostMapping("/{id}/edit-parts/local-upload-chunk")
    public Map<String, Object> uploadEditPartLocalFileChunk(@PathVariable("id") Long id,
                                                            @RequestParam("sessionId") String sessionId,
                                                            @RequestParam("uploadId") String uploadId,
                                                            @RequestParam("fileName") String fileName,
                                                            @RequestParam("chunkIndex") int chunkIndex,
                                                            @RequestParam("totalChunks") int totalChunks,
                                                            @RequestParam("totalSize") long totalSize,
                                                            @RequestParam("chunk") MultipartFile chunk) {
        return publishService.saveEditPartTempFileChunk(id, sessionId, uploadId, fileName, chunkIndex, totalChunks, totalSize, chunk);
    }

    @PostMapping("/{id}/edit-parts/local-upload/cancel")
    public Map<String, Object> cancelEditPartLocalUpload(@PathVariable("id") Long id,
                                                         @RequestBody Map<String, Object> request) {
        return publishService.cancelEditPartTempUpload(
                id,
                String.valueOf(request.getOrDefault("sessionId", "")),
                String.valueOf(request.getOrDefault("uploadId", "")),
                String.valueOf(request.getOrDefault("fileName", ""))
        );
    }

    @PostMapping("/{id}/edit-parts/submit")
    public Map<String, Object> submitEditParts(@PathVariable("id") Long id,
                                               @RequestBody Map<String, Object> request) {
        return publishService.submitEditParts(id, request);
    }

    @GetMapping("/{id}/edit-parts/task")
    public Map<String, Object> editPartsTask(@PathVariable("id") Long id) {
        return publishService.getEditPartsTask(id);
    }

    @PostMapping("/{id}/edit-parts/cleanup")
    public Map<String, Object> cleanupEditParts(@PathVariable("id") Long id,
                                                @RequestBody(required = false) Map<String, Object> request) {
        String sessionId = request == null ? null : String.valueOf(request.getOrDefault("sessionId", ""));
        return publishService.cleanupEditPartTempFiles(id, sessionId);
    }

    @PostMapping("/{id}/edit-parts/restore-online-state")
    public Map<String, Object> restoreEditPartsOnlineState(@PathVariable("id") Long id) {
        return publishService.restoreEditPartsOnlineState(id);
    }

    private LinkedHashSet<String> resolveCandidateFileSearchRoots(RecordHistory history) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        String historyDir = normalizeFsPath(history.getFilePath());
        addCandidateSearchRoot(roots, historyDir);
        if (StringUtils.isNotBlank(historyDir)) {
            File parent = new File(historyDir).getParentFile();
            if (parent != null) {
                addCandidateSearchRoot(roots, parent.getPath());
            }
        }

        String roomId = history.getRoomId();
        if (StringUtils.isNotBlank(roomId)) {
            addCandidateSearchRoot(roots, workPath + "/" + roomId);
            RecordRoom room = roomRepository.findByRoomId(roomId);
            if (room != null && StringUtils.isNotBlank(room.getUname())) {
                addCandidateSearchRoot(roots, workPath + "/" + roomId + "-" + room.getUname());
            }
            File workDir = new File(workPath);
            File[] roomDirs = workDir.listFiles(file -> file.isDirectory() && file.getName().startsWith(roomId + "-"));
            if (roomDirs != null) {
                for (File roomDir : roomDirs) {
                    addCandidateSearchRoot(roots, roomDir.getPath());
                }
            }
        }
        return roots;
    }

    private void addCandidateSearchRoot(Set<String> roots, String dirPath) {
        String normalized = normalizeFsPath(dirPath);
        if (StringUtils.isBlank(normalized) || !isUnderWorkPath(normalized)) {
            return;
        }
        if (isFilesystemRoot(normalized)) {
            log.warn("[BLR] {}", LogKvs.event("History.CandidateFiles.SkipRootDir")
                    .add("root", normalized));
            return;
        }
        String normalizedWork = normalizeFsPath(workPath);
        if (normalizedWork != null
                && normalized.equalsIgnoreCase(normalizedWork.endsWith("/") ? normalizedWork.substring(0, normalizedWork.length() - 1) : normalizedWork)) {
            return;
        }
        File dir = new File(normalized);
        if (dir.exists() && dir.isDirectory()) {
            roots.add(dir.getPath().replace("\\", "/"));
        }
    }

    private void collectCandidateFiles(File root,
                                       String keyword,
                                       String[] allowedExt,
                                       List<Map<String, Object>> items,
                                       Set<String> seenPaths) {
        if (root == null || !root.exists() || !root.isDirectory()) {
            return;
        }
        try {
            Files.walkFileTree(root.toPath(), EnumSet.noneOf(java.nio.file.FileVisitOption.class), 4, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (attrs != null && attrs.isRegularFile()) {
                        addCandidateFile(path, keyword, allowedExt, items, seenPaths);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("[BLR] {}", LogKvs.event("History.CandidateFiles.SkipUnreadablePath")
                            .add("path", file == null ? "" : file.toString())
                            .addIfNotBlank("err", exc == null ? null : exc.getMessage()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("[BLR] {}", LogKvs.event("History.CandidateFiles.ScanFailed")
                    .add("root", root.getPath())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    private void addCandidateFile(Path path,
                                  String keyword,
                                  String[] allowedExt,
                                  List<Map<String, Object>> items,
                                  Set<String> seenPaths) {
        File f = path.toFile();
        String name = f.getName();
        if (name == null) {
            return;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!hasAllowedExt(lower, allowedExt)) {
            return;
        }
        if (keyword != null && !keyword.isEmpty() && lower.indexOf(keyword) < 0) {
            return;
        }
        String filePath = f.getPath().replace("\\", "/");
        if (!seenPaths.add(filePath.toLowerCase(java.util.Locale.ROOT))) {
            return;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("filePath", filePath);
        m.put("size", f.length());
        m.put("lastModified", f.lastModified());
        items.add(m);
    }

    private boolean hasAllowedExt(String lowerName, String[] allowedExt) {
        for (String ext : allowedExt) {
            if (lowerName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnderWorkPath(String path) {
        String normalizedWork = normalizeFsPath(workPath);
        String normalizedPath = normalizeFsPath(path);
        if (StringUtils.isBlank(normalizedWork) || StringUtils.isBlank(normalizedPath)) {
            return false;
        }
        normalizedWork = normalizedWork.endsWith("/") ? normalizedWork : (normalizedWork + "/");
        normalizedPath = normalizedPath.endsWith("/") ? normalizedPath : (normalizedPath + "/");
        return normalizedPath.toLowerCase(java.util.Locale.ROOT).startsWith(normalizedWork.toLowerCase(java.util.Locale.ROOT));
    }

    private String normalizeFsPath(String path) {
        return path == null ? null : path.replace("\\", "/");
    }

    private boolean isFilesystemRoot(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        try {
            return Path.of(path).getParent() == null;
        } catch (Exception e) {
            return false;
        }
    }


    @PostMapping("/update")
    public Map<String, String> update(@RequestBody RecordHistory history) {
        Optional<RecordHistory> historyOptional = historyRepository.findById(history.getId());
        Map<String, String> result = new HashMap<>();
        if (historyOptional.isPresent()) {
            RecordHistory dbHistory = historyOptional.get();
            dbHistory.setRecording(history.isRecording());
            dbHistory.setUpload(history.isUpload());
            dbHistory.setUpdateTime(LocalDateTime.now());
            historyRepository.save(dbHistory);
            result.put("type", "info");
            result.put("msg", "更新成功");
        }
        return result;
    }

    @PostMapping("/visibility/{id}")
    public Map<String, String> updateVisibility(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "warning");
            result.put("msg", "参数错误：缺少稿件ID");
            return result;
        }
        int isOnlySelf;
        try {
            Object raw = body == null ? null : body.get("isOnlySelf");
            isOnlySelf = Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            result.put("type", "warning");
            result.put("msg", "参数错误：isOnlySelf 只能为 0(公开) 或 1(仅自己可见)");
            return result;
        }
        if (isOnlySelf != 0 && isOnlySelf != 1) {
            result.put("type", "warning");
            result.put("msg", "参数错误：isOnlySelf 只能为 0(公开) 或 1(仅自己可见)");
            return result;
        }

        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "稿件不存在");
            return result;
        }
        RecordHistory history = historyOptional.get();

        // 仅允许审核通过状态（公开/仅自己可见）切换可见性
        if (!history.isPublish() || (history.getCode() != 0 && history.getCode() != -50)) {
            result.put("type", "warning");
            result.put("msg", "仅审核通过的稿件（公开/仅自己可见）可切换可见性");
            return result;
        }

        // 仍在录制/上传/投稿流程中，不允许切换
        if (history.isRecording() || TaskUtil.publishTask.containsKey(history.getId())) {
            result.put("type", "warning");
            result.put("msg", "稿件仍在处理中，暂不允许切换可见性");
            return result;
        }

        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
        for (RecordHistoryPart part : parts) {
            if (part == null) {
                continue;
            }
            if (part.isRecording() || part.getEndTime() == null || TaskUtil.partUploadTask.containsKey(part.getId())) {
                result.put("type", "warning");
                result.put("msg", "分P仍在录制或上传处理中，暂不允许切换可见性");
                return result;
            }
        }
        long activeUploadCount = uploadProgressTracker.listByHistoryId(history.getId()).stream()
                .filter(UploadProgressTracker.Progress::isActive)
                .count();
        if (activeUploadCount > 0) {
            result.put("type", "warning");
            result.put("msg", "检测到上传仍在进行中，暂不允许切换可见性");
            return result;
        }

        // 发送弹幕任务尚未完成，不允许切换
        RecordRoom room = roomRepository.findByRoomId(history.getRoomId());
        boolean sendDmEnabled = room != null && Boolean.TRUE.equals(room.getSendDm());
        boolean sendScEnabled = room != null && Boolean.TRUE.equals(room.getSendSc());
        int pendingNormal = 0;
        int pendingHigh = 0;
        if (StringUtils.isNotBlank(history.getBvId())) {
            pendingNormal = sendDmEnabled ? msgRepository.countByBvidAndPoolAndCode(history.getBvId(), 0, -1) : 0;
            pendingHigh = sendScEnabled ? msgRepository.countByBvidAndPoolAndCode(history.getBvId(), 1, -1) : 0;
        }
        if (history.getCode() == -50) {
            // 私有稿件允许普通弹幕队列存在，但若高级弹幕评论仍在发送，不允许手动切换以避免和自动流程冲突
            if (sendScEnabled && pendingHigh > 0) {
                result.put("type", "warning");
                result.put("msg", "稿件仍在发送高级弹幕/评论中，暂不允许切换可见性");
                return result;
            }
        } else {
            if ((sendScEnabled && !history.isSendReply()) || pendingNormal > 0 || pendingHigh > 0) {
                result.put("type", "warning");
                result.put("msg", "稿件仍在发送弹幕/评论中，暂不允许切换可见性");
                return result;
            }
        }

        // 需要可用的投稿账号和 avId
        if (room == null || room.getUploadUserId() == null) {
            result.put("type", "warning");
            result.put("msg", "未配置投稿账号，无法切换可见性");
            return result;
        }
        Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
        if (userOptional.isEmpty() || !userOptional.get().isLogin()) {
            result.put("type", "warning");
            result.put("msg", "投稿账号未登录或不可用，无法切换可见性");
            return result;
        }
        if (StringUtils.isBlank(history.getAvId())) {
            result.put("type", "warning");
            result.put("msg", "稿件缺少 avId，无法切换可见性");
            return result;
        }

        try {
            long aid = Long.parseLong(history.getAvId());
            String apiRes = BiliApi.updateVideoVisibility(userOptional.get(), aid, isOnlySelf);
            JSONObject obj = JSON.parseObject(apiRes);
            int code = obj == null ? -1 : obj.getIntValue("code");
            String msg = obj == null ? "返回为空" : obj.getString("message");
            if (code != 0) {
                result.put("type", "warning");
                result.put("msg", "切换失败：" + (StringUtils.isNotBlank(msg) ? msg : "B站返回错误(" + code + ")"));
                log.warn("[BLR] {}", LogKvs.event("History.Visibility.Switch.Failed")
                        .add("historyId", history.getId())
                        .add("roomId", history.getRoomId())
                        .add("aid", history.getAvId())
                        .add("target", isOnlySelf)
                        .add("code", code)
                    .add("msg", msg)
                    .addRoundCount("pendingNormal", pendingNormal)
                    .addRoundCount("pendingHigh", pendingHigh)
                    .addStageCostMs("total", totalStartNs));
                return result;
            }

            history.setCode(isOnlySelf == 1 ? -50 : 0);
            history.setUpdateTime(LocalDateTime.now());
            historyRepository.save(history);

            result.put("type", "success");
            result.put("msg", isOnlySelf == 1 ? "已切换为仅自己可见" : "已切换为公开");
            log.info("[BLR] {}", LogKvs.event("History.Visibility.Switch.Success")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .add("aid", history.getAvId())
                    .add("target", isOnlySelf)
                    .addRoundCount("pendingNormal", pendingNormal)
                    .addRoundCount("pendingHigh", pendingHigh)
                    .addStageCostMs("total", totalStartNs));
            return result;
        } catch (Exception e) {
            result.put("type", "warning");
            result.put("msg", "切换可见性失败：" + e.getClass().getSimpleName());
            log.error("[BLR] {}", LogKvs.event("History.Visibility.Switch.Error")
                    .add("historyId", history.getId())
                    .add("roomId", history.getRoomId())
                    .add("aid", history.getAvId())
                    .add("target", isOnlySelf)
                    .add("err", e.getMessage())
                    .addRoundCount("pendingNormal", pendingNormal)
                    .addRoundCount("pendingHigh", pendingHigh)
                    .addStageCostMs("total", totalStartNs), e);
            return result;
        }
    }

    @GetMapping("/delete/{id}")
    public Map<String, Object> delete(@PathVariable("id") Long id,
                                      @RequestParam(required = false, defaultValue = "false") boolean deleteVideo,
                                      @RequestParam(required = false, defaultValue = "false") boolean deleteDanmaku,
                                      @RequestParam(required = false, defaultValue = "false") boolean deleteCover) {
        long totalStartNs = System.nanoTime();
        Map<String, Object> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            long msgDeleteStartNs = System.nanoTime();
            int deletedMsgCount = msgRepository.deleteByHistoryId(history.getId());
            long msgDeleteCostMs = toCostMs(msgDeleteStartNs);

            List<Map<String, Object>> notDeletedFiles = new ArrayList<>();
            int localDeleteAttempt = 0;
            int localDeleteSuccess = 0;
            boolean deleteAnyLocal = deleteVideo || deleteDanmaku || deleteCover;

            long localDeleteStartNs = System.nanoTime();
            if (deleteAnyLocal) {
                List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                for (RecordHistoryPart part : partList) {
                    String filePath = part.getFilePath();
                    if (filePath == null) {
                        continue;
                    }
                    if (!filePath.startsWith(workPath)) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", filePath);
                        entry.put("kind", "unknown");
                        entry.put("status", "skipped");
                        entry.put("reason", "文件不在 workPath 下，出于安全跳过");
                        notDeletedFiles.add(entry);
                        continue;
                    }
                    String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                    String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                    File startDir = new File(startDirPath);
                    File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                    if (files == null) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", startDirPath);
                        entry.put("kind", "unknown");
                        entry.put("status", "missing");
                        entry.put("reason", "目录不存在或无法读取");
                        notDeletedFiles.add(entry);
                    } else {
                        if (files.length == 0) {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("path", filePath);
                            entry.put("kind", "unknown");
                            entry.put("status", "missing");
                            entry.put("reason", "未找到匹配的本地文件");
                            notDeletedFiles.add(entry);
                        }
                        for (File file : files) {
                            if (!shouldDeleteFile(file, deleteVideo, deleteDanmaku, deleteCover)) {
                                continue;
                            }
                            localDeleteAttempt++;
                            Path path = file.toPath();
                            String lowerName = file.getName() == null ? "" : file.getName().toLowerCase();
                            String kind = fileKindByLowerName(lowerName);
                            if (!Files.exists(path)) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("path", path.toString().replace("\\", "/"));
                                entry.put("kind", kind);
                                entry.put("status", "missing");
                                entry.put("reason", "文件不存在");
                                notDeletedFiles.add(entry);
                                continue;
                            }
                            try {
                                Files.delete(path);
                                localDeleteSuccess++;
                            } catch (Exception ex) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("path", path.toString().replace("\\", "/"));
                                entry.put("kind", kind);
                                entry.put("status", "failed");
                                entry.put("reason", ex.getClass().getSimpleName() + ": " + String.valueOf(ex.getMessage()));
                                notDeletedFiles.add(entry);
                            }
                        }
                    }
                }
            }

            long localDeleteCostMs = toCostMs(localDeleteStartNs);

            long partDeleteStartNs = System.nanoTime();
            int deletedPartCount = partRepository.deleteByHistoryId(history.getId());
            long partDeleteCostMs = toCostMs(partDeleteStartNs);

            long historyDeleteStartNs = System.nanoTime();
            historyRepository.delete(history);
            long historyDeleteCostMs = toCostMs(historyDeleteStartNs);
            long totalCostMs = toCostMs(totalStartNs);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("historyId", id);
            data.put("deleteVideo", deleteVideo);
            data.put("deleteDanmaku", deleteDanmaku);
            data.put("deleteCover", deleteCover);
            data.put("deletedMsgCount", deletedMsgCount);
            data.put("deletedPartCount", deletedPartCount);
            data.put("localDeleteAttempt", localDeleteAttempt);
            data.put("localDeleteSuccess", localDeleteSuccess);
            data.put("msgDeleteCostMs", msgDeleteCostMs);
            data.put("localDeleteCostMs", localDeleteCostMs);
            data.put("partDeleteCostMs", partDeleteCostMs);
            data.put("historyDeleteCostMs", historyDeleteCostMs);
            data.put("totalCostMs", totalCostMs);
            data.put("round.deletedMsgCount", deletedMsgCount);
            data.put("round.deletedPartCount", deletedPartCount);
            data.put("round.localDeleteAttemptCount", localDeleteAttempt);
            data.put("round.localDeleteSuccessCount", localDeleteSuccess);
            data.put("stage.msgDelete.costMs", msgDeleteCostMs);
            data.put("stage.localDelete.costMs", localDeleteCostMs);
            data.put("stage.partDelete.costMs", partDeleteCostMs);
            data.put("stage.historyDelete.costMs", historyDeleteCostMs);
            data.put("stage.total.costMs", totalCostMs);
            data.put("notDeletedFiles", notDeletedFiles);
            result.put("data", data);

            log.info("[BLR] {}", LogKvs.event("History.Delete.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .add("deleteVideo", deleteVideo)
                    .add("deleteDanmaku", deleteDanmaku)
                    .add("deleteCover", deleteCover)
                    .add("deletedMsgCount", deletedMsgCount)
                    .add("deletedPartCount", deletedPartCount)
                    .add("localDeleteAttempt", localDeleteAttempt)
                    .add("localDeleteSuccess", localDeleteSuccess)
                    .add("msgDeleteCostMs", msgDeleteCostMs)
                    .add("localDeleteCostMs", localDeleteCostMs)
                    .add("partDeleteCostMs", partDeleteCostMs)
                    .add("historyDeleteCostMs", historyDeleteCostMs)
                    .add("totalCostMs", totalCostMs)
                    .addRoundCount("deletedMsg", deletedMsgCount)
                    .addRoundCount("deletedPart", deletedPartCount)
                    .addRoundCount("localDeleteAttempt", localDeleteAttempt)
                    .addRoundCount("localDeleteSuccess", localDeleteSuccess)
                    .addStageField("msgDelete", "costMs", msgDeleteCostMs)
                    .addStageField("localDelete", "costMs", localDeleteCostMs)
                    .addStageField("partDelete", "costMs", partDeleteCostMs)
                    .addStageField("historyDelete", "costMs", historyDeleteCostMs)
                    .addStageField("total", "costMs", totalCostMs));

            if (notDeletedFiles.isEmpty()) {
                result.put("type", "success");
                result.put("msg", "录制历史删除成功");
            } else {
                result.put("type", "warning");
                result.put("msg", "录制历史删除成功（有 " + notDeletedFiles.size() + " 个本地文件未删除）");
                log.warn(LogKvs.event("History.Delete.LocalFileNotDeleted")
                        .msg("删除录制历史时发现本地文件未删除")
                        .add("historyId", id)
                        .add("notDeletedCount", notDeletedFiles.size())
                        .add("localDeleteAttempt", localDeleteAttempt)
                        .add("localDeleteSuccess", localDeleteSuccess)
                        .add("deletedMsgCount", deletedMsgCount)
                        .add("deletedPartCount", deletedPartCount)
                        .add("msgDeleteCostMs", msgDeleteCostMs)
                        .add("localDeleteCostMs", localDeleteCostMs)
                        .add("partDeleteCostMs", partDeleteCostMs)
                        .add("historyDeleteCostMs", historyDeleteCostMs)
                        .add("totalCostMs", totalCostMs)
                        .addRoundCount("deletedMsg", deletedMsgCount)
                        .addRoundCount("deletedPart", deletedPartCount)
                        .addRoundCount("localDeleteAttempt", localDeleteAttempt)
                        .addRoundCount("localDeleteSuccess", localDeleteSuccess)
                        .addStageField("msgDelete", "costMs", msgDeleteCostMs)
                        .addStageField("localDelete", "costMs", localDeleteCostMs)
                        .addStageField("partDelete", "costMs", partDeleteCostMs)
                        .addStageField("historyDelete", "costMs", historyDeleteCostMs)
                        .addStageField("total", "costMs", totalCostMs)
                        .toString());
            }
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    private long toCostMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000;
    }

    private String fileKindByLowerName(String lowerName) {
        if (lowerName == null) return "unknown";
        if (lowerName.endsWith(".mp4") || lowerName.endsWith(".flv") || lowerName.endsWith(".mkv") || lowerName.endsWith(".ts") || lowerName.endsWith(".mov") || lowerName.endsWith(".avi")) {
            return "video";
        }
        if (lowerName.endsWith(".xml") || lowerName.endsWith(".ass")) {
            return "danmaku";
        }
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".png") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif")) {
            return "cover";
        }
        return "other";
    }

    private boolean shouldDeleteFile(File file, boolean deleteVideo, boolean deleteDanmaku, boolean deleteCover) {
        if (!deleteVideo && !deleteDanmaku && !deleteCover) {
            return false;
        }
        String name = file.getName().toLowerCase();
        // 视频文件扩展名
        if (name.endsWith(".mp4") || name.endsWith(".flv") || name.endsWith(".mkv") || name.endsWith(".ts") || name.endsWith(".mov") || name.endsWith(".avi")) {
            return deleteVideo;
        }
        // 弹幕文件扩展名
        if (name.endsWith(".xml") || name.endsWith(".ass")) {
            return deleteDanmaku;
        }
        // 封面文件扩展名
        if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg") || name.endsWith(".webp") || name.endsWith(".gif")) {
            return deleteCover;
        }
        // 其他文件默认删除
        return true;
    }

    @GetMapping("/deleteMsg/{id}")
    public Map<String, String> deleteMsg(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            long queryStartNs = System.nanoTime();
            List<LiveMsg> liveMsgs = msgRepository.queryByBvid(history.getBvId());
            long queryCostMs = toCostMs(queryStartNs);
            long deleteStartNs = System.nanoTime();
            msgRepository.deleteAll(liveMsgs);
            long deleteCostMs = toCostMs(deleteStartNs);
            result.put("type", "success");
            result.put("msg", "弹幕删除成功");
            log.info("[BLR] {}", LogKvs.event("History.DeleteMsg.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .addRoundCount("deletedMsg", liveMsgs.size())
                    .addStageField("query", "costMs", queryCostMs)
                    .addStageField("delete", "costMs", deleteCostMs)
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/reloadMsg/{id}")
    public Map<String, String> reloadMsg(@PathVariable("id") Long id,
                                         @RequestParam(required = false, defaultValue = "false") boolean restartOrdinary,
                                         @RequestParam(required = false, defaultValue = "false") boolean restartAdvanced) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            int scannedParts = 0;
            int reloadedParts = 0;
            int deletedMsgCount = 0;
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : parts) {
                scannedParts++;
                if (restartOrdinary) {
                    top.sshh.bililiverecoder.job.LiveMsgSendSync.skipOrdinaryPartIds.add(part.getId());
                }
                if (restartAdvanced) {
                    top.sshh.bililiverecoder.job.LiveMsgSendSync.skipAdvancedPartIds.add(part.getId());
                }
                String filePath = part.getFilePath();
                filePath = filePath.substring(0, filePath.lastIndexOf(".")) + ".xml";
                File file = new File(filePath);
                if (file.exists()) {
                    List<LiveMsg> liveMsgs = msgRepository.queryByCid(part.getCid());
                    deletedMsgCount += liveMsgs.size();
                    msgRepository.deleteAll(liveMsgs);
                    msgService.processing(part);
                    reloadedParts++;
                }
            }
            history.setSendReply(false);
            historyRepository.save(history);
            result.put("type", "success");
            result.put("msg", "弹幕重新加载成功");
            log.info("[BLR] {}", LogKvs.event("History.ReloadMsg.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .add("restartOrdinary", restartOrdinary)
                    .add("restartAdvanced", restartAdvanced)
                    .addRoundCount("scannedPart", scannedParts)
                    .addRoundCount("reloadedPart", reloadedParts)
                    .addRoundCount("deletedMsg", deletedMsgCount)
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/updatePartStatus/{id}")
    public Map<String, String> updatePartStatus(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            int updatedPartCount = 0;
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                part.setRecording(false);
                partRepository.save(part);
                updatedPartCount++;
            }
            history.setRecording(false);
            historyRepository.save(history);
            result.put("type", "success");
            result.put("msg", "状态更新成功");
            log.info("[BLR] {}", LogKvs.event("History.UpdatePartStatus.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .addRoundCount("updatedPart", updatedPartCount)
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/updatePublishStatus/{id}")
    public Map<String, String> updatePublishStatus(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            LocalDateTime now = LocalDateTime.now();
            if (history.getStartTime() != null) {
                history.setStartTime(history.getStartTime().plusMinutes(1L));
            }
            history.setRecording(false);
            history.setStreaming(false);
            history.setUpload(true);
            history.setPublish(false);
            history.setAvId(null);
            history.setBvId(null);
            history.setSendReply(false);
            history.setForceArchived(false);
            history.setPublishUserId(null);
            history.setCode(-1);
            history.setUploadRetryCount(0);
            history.setUpdateTime(now);
            int resetPartCount = 0;
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                part.setRecording(false);
                part.setUpload(false);
                part.setCid(null);
                part.setFileName(null);
                part.setUploadRetryCount(0);
                part.setDeleteFailType(null);
                part.setDeleteFailReason(null);
                part.setUploadFlow(null);
                part.setUploadFlowFallback(false);
                part.setUploadFlowFallbackReason(null);
                part.setUpdateTime(now);
                partRepository.save(part);
                resetPartCount++;
            }
            historyRepository.save(history);
            result.put("type", "success");
            result.put("msg", "状态已重置，将重新上传并投稿");
            log.info("[BLR] {}", LogKvs.event("History.UpdatePublishStatus.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .addRoundCount("resetPart", resetPartCount)
                    .add("resetUpload", true)
                    .add("resetPublish", true)
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/touchPublish/{id}")
    public Map<String, String> touchPublish(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            history.setUploadRetryCount(0);
            history = historyRepository.save(history);
            publishService.asyncPublishRecordHistory(history);
            result.put("type", "success");
            result.put("msg", "触发发布事件成功");
            log.info("[BLR] {}", LogKvs.event("History.TouchPublish.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/highEnergyCutPublish/{id}")
    public Map<String, String> HighEnergyCutPublish(@PathVariable("id") Long id) throws IOException {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            history.setUploadRetryCount(0);
            history = historyRepository.save(history);
            String msg = HighEnergyCutPublishService.taskRunningMsg.get(history.getId());
            if (msg != null) {
                result.put("type", "warning");
                result.put("msg", "正在剪辑处理\n" + msg);
                return result;
            }
            try {
                highEnergyCutPublishService.process(history);
            } catch (Exception e) {
                HighEnergyCutPublishService.taskRunningMsg.remove(history.getId());
                result.put("type", "error");
                result.put("msg", e.getMessage());
                return result;
            }
            result.put("type", "success");
            result.put("msg", "触发高能剪辑成功");
                log.info("[BLR] {}", LogKvs.event("History.HighEnergyCutPublish.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/rePublish/{id}")
    public Map<String, String> rePublish(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            history.setUploadRetryCount(0);
            history.setForceArchived(false);
            history = historyRepository.save(history);
            publishService.asyncRepublishRecordHistory(history);
            result.put("type", "success");
            result.put("msg", "触发转码修复事件成功");
            log.info("[BLR] {}", LogKvs.event("History.Republish.Success")
                    .add("historyId", id)
                    .add("roomId", history.getRoomId())
                    .addStageCostMs("total", totalStartNs));
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/forceArchive/{id}")
    public Map<String, String> forceArchive(@PathVariable("id") Long id) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            boolean changed = false;
            boolean uploadClosed = false;

            if (!history.isForceArchived()) {
                history.setForceArchived(true);
                changed = true;
            }

            // 如果正在发弹幕 -> 强制标记为已发完
            if (history.isPublish() && (history.getCode() == 0 || history.getCode() == -50) && !history.isSendReply()) {
                history.setSendReply(true);
                changed = true;
            }

            // 如果正在直播中 -> 强制停止直播状态
            if (history.isStreaming()) {
                history.setStreaming(false);
                changed = true;
            }

            // 如果正在录制 -> 强制停止录制状态
            if (history.isRecording()) {
                history.setRecording(false);
                List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
                for (RecordHistoryPart part : parts) {
                    if (part.isRecording()) {
                        part.setRecording(false);
                        partRepository.save(part);
                    }
                }
                changed = true;
            }

            // 如果正在上传/处理中 -> 强制关闭上传
            // 定义处理中: upload=true 且 (publish=false 或 code是处理中状态)
            if (history.isUpload()) {
                history.setUpload(false);
                uploadClosed = true;
                changed = true;
            }

            if (changed) {
                historyRepository.save(history);
                result.put("type", "success");
                result.put("msg", "已强制归档");
                log.info("[BLR] {}", LogKvs.event("History.ForceArchive.Success")
                        .add("historyId", id)
                        .add("roomId", history.getRoomId())
                        .add("uploadClosed", uploadClosed)
                        .addStageCostMs("total", totalStartNs));
            } else {
                result.put("type", "info");
                result.put("msg", "该稿件不满足强制归档条件（可能已归档）");
            }
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }


     // 动态查询条件（复用于列表查询和总数统计）
    private List<Predicate> getPredicates(CriteriaBuilder criteriaBuilder, Root<RecordHistory> root, RecordHistoryDTO request) {
        List<Predicate> predicatesList = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getRoomId())) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("roomId"), request.getRoomId())));
        }
        if (StringUtils.isNotBlank(request.getBvId())) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.like(root.get("bvId"), "%" + request.getBvId() + "%")));
        }
        if (StringUtils.isNotBlank(request.getTitle())) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.like(root.get("title"), "%" + request.getTitle() + "%")));
        }

        if (request.getRecording() != null) {
            // 录制状态筛选：以分P真实状态为准。
            // 只要存在 recording=true 或 endTime=null 的分P，就视为“录制中”。
            Subquery<Long> partExists = criteriaBuilder.createQuery().subquery(Long.class);
            Root<RecordHistoryPart> partRoot = partExists.from(RecordHistoryPart.class);
            partExists.select(criteriaBuilder.literal(1L));
            partExists.where(
                criteriaBuilder.and(
                    criteriaBuilder.equal(partRoot.get("historyId"), root.get("id")),
                    criteriaBuilder.or(
                        criteriaBuilder.isTrue(partRoot.get("recording")),
                        criteriaBuilder.isNull(partRoot.get("endTime"))
                    )
                )
            );
            if (Boolean.TRUE.equals(request.getRecording())) {
                predicatesList.add(criteriaBuilder.exists(partExists));
            } else {
                predicatesList.add(criteriaBuilder.not(criteriaBuilder.exists(partExists)));
            }
        }
        if (request.getUpload() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("upload"), request.getUpload())));
        }
        if (request.getPublish() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("publish"), request.getPublish())));
        }
        if (request.getCode() != null) {
            // 当筛选审核状态时，需要同时检查publish状态。
            // 只有已发布的视频才有真正的审核状态，未发布的视频code默认值是-1但不应被当作"审核中"。
            // 前端约定：code=-999 表示“未审核/未发布”（publish=false）。
            // 前端值：code=1 表示“未通过/不通过”（即已发布但审核状态非 通过/仅自己可见）。
            if (Objects.equals(request.getCode(), -999)) {
                predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("publish"), false)));
            } else if (Objects.equals(request.getCode(), 1)) {
                predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("publish"), true)));
                predicatesList.add(criteriaBuilder.and(criteriaBuilder.not(root.get("code").in(0, -50))));
            } else {
                predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("code"), request.getCode())));
                predicatesList.add(criteriaBuilder.and(criteriaBuilder.equal(root.get("publish"), true)));
            }
        }

        if (request.getFrom() != null && request.getTo() != null) {
            predicatesList.add(criteriaBuilder.and(criteriaBuilder.between(root.get("endTime"), request.getFrom(), request.getTo())));
        }

        if (StringUtils.isNotBlank(request.getViewType())) {
            // 定义归档状态（已完成）：
            // 1. 已发布 (publish = true) 且 稿件状态正常 (code 为 0 或 -50) 且（已发送评论 或 房间关闭全部弹幕开关）
            // 2. 或者：设置为不上传 (upload = false) 且 录制已结束 (不存在正在录制的分P)

            Predicate isArchived = buildFullArchivedPredicate(criteriaBuilder, root);

            if ("working".equals(request.getViewType())) {
                // 工作中 = 非归档
                predicatesList.add(criteriaBuilder.not(isArchived));
            } else if ("archived".equals(request.getViewType())) {
                predicatesList.add(isArchived);
            }
        }

        return predicatesList;
    }

    private Predicate buildPublishedArchivedPredicate(CriteriaBuilder criteriaBuilder, Root<RecordHistory> root) {
        Subquery<String> disabledRooms = criteriaBuilder.createQuery().subquery(String.class);
        Root<RecordRoom> roomRoot = disabledRooms.from(RecordRoom.class);
        disabledRooms.select(roomRoot.get("roomId"));
        Predicate dmOff = criteriaBuilder.or(criteriaBuilder.isNull(roomRoot.get("sendDm")), criteriaBuilder.isFalse(roomRoot.get("sendDm")));
        Predicate scOff = criteriaBuilder.or(criteriaBuilder.isNull(roomRoot.get("sendSc")), criteriaBuilder.isFalse(roomRoot.get("sendSc")));
        disabledRooms.where(criteriaBuilder.and(dmOff, scOff));
        Predicate allDmDisabled = root.get("roomId").in(disabledRooms);

        return criteriaBuilder.and(
                criteriaBuilder.equal(root.get("publish"), true),
                root.get("code").in(0, -50),
                criteriaBuilder.or(criteriaBuilder.equal(root.get("sendReply"), true), allDmDisabled)
        );
    }

    private Predicate buildFullArchivedPredicate(CriteriaBuilder criteriaBuilder, Root<RecordHistory> root) {
        Predicate isForceArchived = criteriaBuilder.equal(root.get("forceArchived"), true);

        // 正常上传并完成的条件
        Predicate isPublishedArchived = buildPublishedArchivedPredicate(criteriaBuilder, root);

        // 设置为不上传且录制已结束的条件
        Subquery<Long> recordingPartExists = criteriaBuilder.createQuery().subquery(Long.class);
        Root<RecordHistoryPart> recordingPartRoot = recordingPartExists.from(RecordHistoryPart.class);
        recordingPartExists.select(criteriaBuilder.literal(1L));
        recordingPartExists.where(
                criteriaBuilder.and(
                        criteriaBuilder.equal(recordingPartRoot.get("historyId"), root.get("id")),
                        criteriaBuilder.or(
                                criteriaBuilder.isTrue(recordingPartRoot.get("recording")),
                                criteriaBuilder.isNull(recordingPartRoot.get("endTime"))
                        )
                )
        );
        Predicate isNoUploadArchived = criteriaBuilder.and(
                criteriaBuilder.equal(root.get("upload"), false),
                criteriaBuilder.not(criteriaBuilder.exists(recordingPartExists))
        );

        return criteriaBuilder.or(isForceArchived, isPublishedArchived, isNoUploadArchived);
    }

    /**
     * 填充RecordHistory的额外字段，包括分P统计、放弃分P信息等。
     * 注意：房间名(roomName)需要调用方单独设置，通常通过roomCache获取。
     * 此方法用于确保返回给前端的数据一致性。
     */
    private void populateHistoryFields(RecordHistory history,
                                       Map<String, String> configMap,
                                       RecordRoom room,
                                       PartListStats partStats,
                                       MsgListStats msgStats) {
        if (history == null) {
            return;
        }
        history.setRoomUpload(room == null ? null : room.isUpload());

        // 动态计算该稿件下所有分P的总文件大小，避免历史数据统计遗漏或0B问题
        long totalFileSize = partStats == null ? partRepository.sumHistoryFileSizeByHistoryId(history.getId()) : partStats.totalFileSize();
        if (totalFileSize > 0 || history.getFileSize() == 0) {
            history.setFileSize(totalFileSize);
        }
        
        // 分P统计
        history.setPartCount(partStats == null ? partRepository.countByHistoryId(history.getId()) : partStats.partCount());
        history.setPartDuration(partStats == null ? partRepository.sumHistoryDurationByHistoryId(history.getId()) : partStats.partDuration());
        history.setUploadPartCount(partStats == null ? partRepository.countByHistoryIdAndFileNameNotNull(history.getId()) : partStats.uploadPartCount());

        // 标记“永久放弃上传”的分P
        int giveUpCount = 0;
        try {
            giveUpCount = partStats == null ? partRepository.countGiveUpPartsByHistoryId(history.getId()) : partStats.giveUpPartCount();
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("History.GiveUpCount.QueryFailed")
                    .add("historyId", history.getId())
                    .add("err", e.getMessage()), e);
        }
        history.setGiveUpPartCount(giveUpCount);        
        // 计算真正的异常分P数量（排除低于阈值和手动跳过的）
        int abnormalCount = 0;
        try {
            abnormalCount = partStats == null ? partRepository.countAbnormalPartsByHistoryId(history.getId()) : partStats.abnormalPartCount();
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("History.AbnormalCount.QueryFailed")
                    .add("historyId", history.getId())
                    .add("err", e.getMessage()), e);
        }
        history.setAbnormalPartCount(abnormalCount);

        int uploadFlowFallbackCount = 0;
        try {
            uploadFlowFallbackCount = partStats == null ? partRepository.countUploadFlowFallbackPartsByHistoryId(history.getId()) : partStats.uploadFlowFallbackCount();
            history.setUploadFlowFallbackCount(uploadFlowFallbackCount);
            if (uploadFlowFallbackCount > 0) {
                List<RecordHistoryPart> fallbackParts = partRepository.findUploadFlowFallbackPartsByHistoryId(history.getId());
                history.setUploadFlowFallbackReasons(fallbackParts.stream()
                        .map(p -> StringUtils.defaultIfBlank(p.getUploadFlowFallbackReason(), "multipart fallback to legacy"))
                        .toList());
            }
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("History.UploadFlowFallback.QueryFailed")
                    .add("historyId", history.getId())
                    .add("err", e.getMessage()), e);
        }

        if (giveUpCount > 0) {
            try {
                List<RecordHistoryPart> giveUpParts = partRepository.findGiveUpPartsByHistoryId(history.getId());
                history.setGiveUpPartFiles(giveUpParts.stream().map(RecordHistoryPart::getFilePath).toList());
                history.setGiveUpPartReasons(giveUpParts.stream().map(p -> {
                    if (StringUtils.isNotBlank(p.getDeleteFailReason())) {
                        return p.getDeleteFailReason();
                    }
                    String t = p.getDeleteFailType();
                    if (StringUtils.isBlank(t)) {
                        return "上传失败/已放弃";
                    }
                    return switch (t) {
                        case "FILE_MISSING" -> "稿件分P文件不存在";
                        case "CID_MISSING" -> "分P缺失CID";
                        case "TIMESTAMP_JUMP" -> "分P时间戳跳变(文件损坏)";
                        case "FILE_SIZE_INVALID" -> "分P文件大小异常";
                        case "DURATION_INVALID" -> "分P时长异常";
                        case "UPLOAD_FAILED" -> "分P上传失败";
                        case "SKIPPED_THRESHOLD" -> "文件低于阈值已跳过";
                        case "MANUAL_SKIP" -> "用户已标记结束/跳过";
                        default -> "上传失败/已放弃";
                    };
                }).toList());
                history.setGiveUpPartTypes(giveUpParts.stream().map(p -> StringUtils.isNotBlank(p.getDeleteFailType()) ? p.getDeleteFailType() : "UNKNOWN").toList());
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("History.GiveUpParts.QueryFailed")
                        .add("historyId", history.getId())
                        .add("err", e.getMessage()), e);
            }
        }

        // 计算实际录制中的分P数量
        int actuallyRecordingParts = partStats == null ? partRepository.countActuallyRecordingPartsByHistoryId(history.getId()) : partStats.recordingPartCount();
        history.setRecordPartCount(actuallyRecordingParts);
        history.setRecording(actuallyRecordingParts > 0);

        // 弹幕统计
        if (StringUtils.isNotBlank(history.getBvId())) {
            history.setMsgCount(msgStats == null ? msgRepository.countByBvid(history.getBvId()) : msgStats.msgCount());
            history.setSuccessMsgCount(msgStats == null ? msgRepository.countByBvidAndCode(history.getBvId(), 0) : msgStats.successMsgCount());
            history.setNormalMsgCount(msgStats == null ? msgRepository.countByBvidAndPool(history.getBvId(), 0) : msgStats.normalMsgCount());
            history.setScMsgCount(msgStats == null ? msgRepository.countByBvidAndPoolAndContextStartingWith(history.getBvId(), 1, "SC [") : msgStats.scMsgCount());
            history.setGuardMsgCount(msgStats == null ? msgRepository.countByBvidAndPoolAndContextStartingWith(history.getBvId(), 1, "⚓") : msgStats.guardMsgCount());

            // 发送开关与待发送数量（仅用于状态展示，不影响后台任务）
            boolean sendDm = room != null && Boolean.TRUE.equals(room.getSendDm());
            boolean sendSc = room != null && Boolean.TRUE.equals(room.getSendSc());
            history.setRoomSendDm(sendDm);
            history.setRoomSendSc(sendSc);
            history.setPendingNormalMsgCount(sendDm ? (msgStats == null ? msgRepository.countByBvidAndPoolAndCode(history.getBvId(), 0, -1) : msgStats.pendingNormalMsgCount()) : 0);
            history.setPendingHighMsgCount(sendSc ? (msgStats == null ? msgRepository.countByBvidAndPoolAndCode(history.getBvId(), 1, -1) : msgStats.pendingHighMsgCount()) : 0);
        }

    // 计算是否处于等待投稿状态
    boolean waitingForPublish = false;
    if (!history.isForceArchived() && history.isUpload() && !history.isPublish() && !history.isRecording() 
            && history.getGiveUpPartCount() == 0 && history.getPartCount() > 0
            && history.getUploadPartCount() == history.getPartCount() && history.getEndTime() != null) {
        
        // 使用外部传入的配置映射，避免在循环中重复查询数据库
        String mergeIntervalConfig = configMap.get(top.sshh.bililiverecoder.service.SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
        int mergeIntervalMinutes = 20; // 默认值
        try {
            if (mergeIntervalConfig != null && !mergeIntervalConfig.isEmpty()) {
                mergeIntervalMinutes = Integer.parseInt(mergeIntervalConfig);
                // 范围校验：1-1440分钟，超出范围自动修正
                if (mergeIntervalMinutes < 1) {
                    mergeIntervalMinutes = 1;
                } else if (mergeIntervalMinutes > 1440) {
                    mergeIntervalMinutes = 1440;
                }
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("History.MergeInterval.InvalidConfig")
                    .add("historyId", history.getId())
                    .add("configValue", mergeIntervalConfig)
                    .add("error", e.getMessage()));
        }
        
        // 计算距离结束时间经过的分钟数
        long minutesSinceEnd = java.time.Duration.between(history.getEndTime(), LocalDateTime.now()).toMinutes();
        if (minutesSinceEnd >= 0 && minutesSinceEnd < mergeIntervalMinutes) {
            waitingForPublish = true;
        }
    }
    history.setWaitingForPublish(waitingForPublish);
    }

    private Map<Long, RoomLiveSessionStats> buildPageSessionStats(List<RecordHistory> histories) {
        List<Long> historyIds = histories.stream()
                .map(RecordHistory::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (historyIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return sessionStatsRepository.findByHistoryIdIn(historyIds).stream()
                .filter(stats -> stats.getHistoryId() != null)
                .collect(java.util.stream.Collectors.toMap(RoomLiveSessionStats::getHistoryId, stats -> stats, (a, b) -> a));
    }

    private Map<Long, PartListStats> buildPagePartStats(List<RecordHistory> histories) {
        List<Long> historyIds = histories.stream()
                .map(RecordHistory::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (historyIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, PartListStats> result = new HashMap<>();
        for (Object[] row : partRepository.aggregateListStatsByHistoryIds(historyIds)) {
            Long historyId = toLong(row[0]);
            if (historyId != null) {
                result.put(historyId, new PartListStats(
                        toInt(row[1]),
                        toFloat(row[2]),
                        toLongValue(row[3]),
                        toInt(row[4]),
                        toInt(row[5]),
                        toInt(row[6]),
                        toInt(row[7]),
                        toInt(row[8])
                ));
            }
        }
        return result;
    }

    private Map<String, MsgListStats> buildPageMsgStats(List<RecordHistory> histories,
                                                        Map<Long, RoomLiveSessionStats> sessionStatsMap) {
        Map<String, MsgListStats> result = new HashMap<>();
        Map<String, SendListStats> sendStatsMap = buildPageSendStats(histories);
        for (RecordHistory history : histories) {
            if (history == null || StringUtils.isBlank(history.getBvId())) {
                continue;
            }
            RoomLiveSessionStats stats = sessionStatsMap.get(history.getId());
            if (stats == null) {
                continue;
            }
            SendListStats sendStats = sendStatsMap.getOrDefault(history.getBvId(), SendListStats.EMPTY);
            int msgCount = safeLongToInt(stats.getMsgCount());
            result.put(history.getBvId(), new MsgListStats(
                    msgCount,
                    sendStats.successMsgCount(),
                    safeLongToInt(stats.getNormalMsgCount()),
                    safeLongToInt(stats.getScCount()),
                    safeLongToInt(stats.getGuardCount()),
                    sendStats.pendingNormalMsgCount(),
                    sendStats.pendingHighMsgCount()
            ));
        }
        List<String> bvids = histories.stream()
                .map(RecordHistory::getBvId)
                .filter(StringUtils::isNotBlank)
                .filter(bvid -> !result.containsKey(bvid))
                .distinct()
                .toList();
        if (bvids.isEmpty()) {
            return result;
        }
        for (Object[] row : msgRepository.aggregateListStatsByBvids(bvids, "SC [%", "⚓%")) {
            String bvid = row[0] == null ? null : row[0].toString();
            if (StringUtils.isNotBlank(bvid)) {
                SendListStats sendStats = sendStatsMap.getOrDefault(bvid, SendListStats.EMPTY);
                result.put(bvid, new MsgListStats(
                        toInt(row[1]),
                        sendStats.successMsgCount(),
                        toInt(row[3]),
                        toInt(row[4]),
                        toInt(row[5]),
                        sendStats.pendingNormalMsgCount(),
                        sendStats.pendingHighMsgCount()
                ));
            }
        }
        return result;
    }

    private Map<String, SendListStats> buildPageSendStats(List<RecordHistory> histories) {
        List<String> bvids = histories.stream()
                .map(RecordHistory::getBvId)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        if (bvids.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, SendListStats> result = new HashMap<>();
        for (Object[] row : msgRepository.aggregateSendStatsByBvids(bvids)) {
            String bvid = row[0] == null ? null : row[0].toString();
            if (StringUtils.isNotBlank(bvid)) {
                result.put(bvid, new SendListStats(
                        toInt(row[1]),
                        toInt(row[2]),
                        toInt(row[3])
                ));
            }
        }
        return result;
    }

    private int safeLongToInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private long toLongValue(Object value) {
        Long longValue = toLong(value);
        return longValue == null ? 0L : longValue;
    }

    private int toInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private float toFloat(Object value) {
        if (value == null) {
            return 0F;
        }
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return Float.parseFloat(value.toString());
    }

    private record PartListStats(int partCount,
                                 float partDuration,
                                 long totalFileSize,
                                 int uploadPartCount,
                                 int recordingPartCount,
                                 int giveUpPartCount,
                                 int abnormalPartCount,
                                 int uploadFlowFallbackCount) {
    }

    private record MsgListStats(int msgCount,
                                int successMsgCount,
                                int normalMsgCount,
                                int scMsgCount,
                                int guardMsgCount,
                                int pendingNormalMsgCount,
                                int pendingHighMsgCount) {
    }

    private record SendListStats(int successMsgCount,
                                 int pendingNormalMsgCount,
                                 int pendingHighMsgCount) {
        private static final SendListStats EMPTY = new SendListStats(0, 0, 0);
    }
}
