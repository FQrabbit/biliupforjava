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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.service.impl.HighEnergyCutPublishService;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.service.SystemConfigService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private RecordBiliPublishService publishService;
    @Autowired
    private LiveMsgRepository msgRepository;
    @Autowired
    private LiveMsgService msgService;
    @Autowired
    private HighEnergyCutPublishService highEnergyCutPublishService;
    @Autowired
    private top.sshh.bililiverecoder.job.videoSyncJob videoSyncJob;
    @Autowired
    private SystemConfigService systemConfigService;
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
        
        // 同步执行数据库查询操作，避免并行流中的 EntityManager 会话问题
        for (RecordHistory history : list) {
            history.setRoomName(roomCache.get(history.getRoomId()));
            // 使用统一方法填充额外字段（分P统计、放弃分P、弹幕统计等）
            populateHistoryFields(history, configMap, roomEntityCache.get(history.getRoomId()));
        }
        Map<String,Object> result = new HashMap<>();
        result.put("data",list);
        result.put("total",total);

        // 统计“工作中”和“已归档”的稿件总数
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
        String baseDir = history.getFilePath();
        if (StringUtils.isBlank(baseDir)) {
            baseDir = workPath + "/" + history.getRoomId();
        }
        baseDir = baseDir.replace("\\", "/");
        String normalizedWork = workPath.endsWith("/") ? workPath : (workPath + "/");
        if (!baseDir.startsWith(normalizedWork)) {
            result.put("items", List.of());
            return result;
        }
        File dir = new File(baseDir);
        if (!dir.exists() || !dir.isDirectory()) {
            result.put("items", List.of());
            return result;
        }

        String kw = keyword == null ? null : keyword.trim().toLowerCase(java.util.Locale.ROOT);
        int max = Math.max(1, Math.min(limit, 500));
        String[] allowedExt = new String[]{".flv", ".mp4", ".ts", ".mkv", ".mov", ".m4v"};

        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            result.put("items", List.of());
            return result;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (File f : files) {
            if (!f.isFile()) {
                continue;
            }
            String name = f.getName();
            if (name == null) {
                continue;
            }
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            boolean ok = false;
            for (String ext : allowedExt) {
                if (lower.endsWith(ext)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            if (kw != null && !kw.isEmpty() && lower.indexOf(kw) < 0) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("filePath", f.getPath().replace("\\", "/"));
            m.put("size", f.length());
            m.put("lastModified", f.lastModified());
            items.add(m);
        }

        items.sort((a, b) -> Long.compare(((Number) b.getOrDefault("lastModified", 0)).longValue(), ((Number) a.getOrDefault("lastModified", 0)).longValue()));
        if (items.size() > max) {
            items = items.subList(0, max);
        }

        result.put("items", items);
        return result;
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

    @GetMapping("/delete/{id}")
    public Map<String, Object> delete(@PathVariable("id") Long id,
                                      @RequestParam(required = false, defaultValue = "false") boolean deleteVideo,
                                      @RequestParam(required = false, defaultValue = "false") boolean deleteDanmaku,
                                      @RequestParam(required = false, defaultValue = "false") boolean deleteCover) {
        Map<String, Object> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            List<LiveMsg> liveMsgs = msgRepository.queryByBvid(history.getBvId());
            msgRepository.deleteAll(liveMsgs);
            List<Map<String, Object>> notDeletedFiles = new ArrayList<>();
            int localDeleteAttempt = 0;
            int localDeleteSuccess = 0;
            boolean deleteAnyLocal = deleteVideo || deleteDanmaku || deleteCover;
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();
                if (filePath == null) {
                    part.setFileDelete(true);
                    partRepository.save(part);
                    continue;
                }
                if (!filePath.startsWith(workPath)) {
                    if (deleteAnyLocal) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", filePath);
                        entry.put("kind", "unknown");
                        entry.put("status", "skipped");
                        entry.put("reason", "文件不在 workPath 下，出于安全跳过");
                        notDeletedFiles.add(entry);
                    }
                    part.setFileDelete(true);
                    partRepository.save(part);
                    continue;
                }
                String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                File startDir = new File(startDirPath);
                File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                if (files == null) {
                    if (deleteAnyLocal) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("path", startDirPath);
                        entry.put("kind", "unknown");
                        entry.put("status", "missing");
                        entry.put("reason", "目录不存在或无法读取");
                        notDeletedFiles.add(entry);
                    }
                } else {
                    if (files.length == 0 && deleteAnyLocal) {
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
                part.setFileDelete(true);
                partRepository.save(part);
            }
            partRepository.deleteAll(partList);
            historyRepository.delete(history);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("historyId", id);
            data.put("deleteVideo", deleteVideo);
            data.put("deleteDanmaku", deleteDanmaku);
            data.put("deleteCover", deleteCover);
            data.put("localDeleteAttempt", localDeleteAttempt);
            data.put("localDeleteSuccess", localDeleteSuccess);
            data.put("notDeletedFiles", notDeletedFiles);
            result.put("data", data);
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
                        .toString());
            }
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
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
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            List<LiveMsg> liveMsgs = msgRepository.queryByBvid(history.getBvId());
            msgRepository.deleteAll(liveMsgs);
            result.put("type", "success");
            result.put("msg", "弹幕删除成功");
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
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : parts) {
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
                    msgRepository.deleteAll(liveMsgs);
                    msgService.processing(part);
                }
            }
            history.setSendReply(false);
            historyRepository.save(history);
            result.put("type", "success");
            result.put("msg", "弹幕重新加载成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/updatePartStatus/{id}")
    public Map<String, String> updatePartStatus(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                part.setRecording(false);
                partRepository.save(part);
            }
            history.setRecording(false);
            historyRepository.save(history);
            result.put("type", "success");
            result.put("msg", "状态更新成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/updatePublishStatus/{id}")
    public Map<String, String> updatePublishStatus(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(id);
        if (historyOptional.isPresent()) {
            RecordHistory history = historyOptional.get();
            history.setStartTime(history.getStartTime().plusMinutes(1L));
            history.setPublish(false);
            history.setBvId(null);
            history.setCode(-1);
            // 重置上传重试次数
            history.setUploadRetryCount(0);
            historyRepository.save(history);
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                part.setUpload(false);
                // 清空已上传的文件名标记，否则进度条会误认为已完成
                part.setFileName(null);
                // 重置分P上传重试次数
                part.setUploadRetryCount(0);
                partRepository.save(part);
            }
            result.put("type", "success");
            result.put("msg", "状态更新成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/touchPublish/{id}")
    public Map<String, String> touchPublish(@PathVariable("id") Long id) {
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
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/highEnergyCutPublish/{id}")
    public Map<String, String> HighEnergyCutPublish(@PathVariable("id") Long id) throws IOException {
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
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/rePublish/{id}")
    public Map<String, String> rePublish(@PathVariable("id") Long id) {
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
            publishService.asyncRepublishRecordHistory(history);
            result.put("type", "success");
            result.put("msg", "触发转码修复事件成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
    }

    @GetMapping("/forceArchive/{id}")
    public Map<String, String> forceArchive(@PathVariable("id") Long id) {
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
            boolean isProcessing = history.isUpload() && (!history.isPublish() || Arrays.asList(-1, -9, -30).contains(history.getCode()));
            if (isProcessing) {
                history.setUpload(false);
                changed = true;
            }

            if (changed) {
                historyRepository.save(history);
                result.put("type", "success");
                result.put("msg", "已强制归档");
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

        return criteriaBuilder.or(isPublishedArchived, isNoUploadArchived);
    }

    /**
     * 填充RecordHistory的额外字段，包括分P统计、放弃分P信息等。
     * 注意：房间名(roomName)需要调用方单独设置，通常通过roomCache获取。
     * 此方法用于确保返回给前端的数据一致性。
     */
    private void populateHistoryFields(RecordHistory history, Map<String, String> configMap, RecordRoom room) {
        if (history == null) {
            return;
        }
        // 分P统计
        history.setPartCount(partRepository.countByHistoryId(history.getId()));
        history.setPartDuration(partRepository.sumHistoryDurationByHistoryId(history.getId()));
        history.setUploadPartCount(partRepository.countByHistoryIdAndFileNameNotNull(history.getId()));

        // 标记“永久放弃上传”的分P
        int giveUpCount = 0;
        try {
            giveUpCount = partRepository.countGiveUpPartsByHistoryId(history.getId());
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("History.GiveUpCount.QueryFailed")
                    .add("historyId", history.getId())
                    .add("err", e.getMessage()), e);
        }
        history.setGiveUpPartCount(giveUpCount);
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
        int actuallyRecordingParts = partRepository.countActuallyRecordingPartsByHistoryId(history.getId());
        history.setRecordPartCount(actuallyRecordingParts);
        history.setRecording(actuallyRecordingParts > 0);

        // 弹幕统计
        if (StringUtils.isNotBlank(history.getBvId())) {
            history.setMsgCount(msgRepository.countByBvid(history.getBvId()));
            history.setSuccessMsgCount(msgRepository.countByBvidAndCode(history.getBvId(), 0));
            history.setNormalMsgCount(msgRepository.countByBvidAndPool(history.getBvId(), 0));
            history.setScMsgCount(msgRepository.countByBvidAndPoolAndContextStartingWith(history.getBvId(), 1, "SC ["));
            history.setGuardMsgCount(msgRepository.countByBvidAndPoolAndContextStartingWith(history.getBvId(), 1, "⚓"));

            // 发送开关与待发送数量（仅用于状态展示，不影响后台任务）
            boolean sendDm = room != null && Boolean.TRUE.equals(room.getSendDm());
            boolean sendSc = room != null && Boolean.TRUE.equals(room.getSendSc());
            history.setRoomSendDm(sendDm);
            history.setRoomSendSc(sendSc);
            history.setPendingNormalMsgCount(sendDm ? msgRepository.countByBvidAndPoolAndCode(history.getBvId(), 0, -1) : 0);
            history.setPendingHighMsgCount(sendSc ? msgRepository.countByBvidAndPoolAndCode(history.getBvId(), 1, -1) : 0);
        }

    // 计算是否处于等待投稿状态
    boolean waitingForPublish = false;
    if (history.isUpload() && !history.isPublish() && !history.isRecording() 
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
}
