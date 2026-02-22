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
        
        Map<String,String> roomCache = new HashMap<>();
        Iterable<RecordRoom> iterable = roomRepository.findAll();
        for (RecordRoom recordRoom : iterable) {
            roomCache.put(recordRoom.getRoomId(),recordRoom.getUname());
        }
        
        // 同步执行数据库查询操作，避免并行流中的 EntityManager 会话问题
        for (RecordHistory history : list) {
            history.setRoomName(roomCache.get(history.getRoomId()));
            // 使用统一方法填充额外字段（分P统计、放弃分P、弹幕统计等）
            populateHistoryFields(history);
        }
        Map<String,Object> result = new HashMap<>();
        result.put("data",list);
        result.put("total",total);

        // 统计“工作中”和“已归档”的稿件总数
        try {
            // 定义归档状态（已完成）：
            // 1. 已发布 (publish = true)
            // 2. 稿件状态正常 (code 为 0 或 -50)
            // 3. 弹幕发送完成 (sendReply = true)
            Predicate isArchived = criteriaBuilder.and(
                    criteriaBuilder.equal(root.get("publish"), true),
                    root.get("code").in(0, -50),
                    criteriaBuilder.equal(root.get("sendReply"), true)
            );

            // 计算“工作中”的稿件数量
            CriteriaQuery<Long> workingQuery = criteriaBuilder.createQuery(Long.class);
            Root<RecordHistory> workingRoot = workingQuery.from(RecordHistory.class);
            workingQuery.select(criteriaBuilder.count(workingRoot));
            
            // 重新构建归档状态的判断条件（因为查询的主体对象变了，所以需要重建条件）
            Predicate isArchivedForWorking = criteriaBuilder.and(
                    criteriaBuilder.equal(workingRoot.get("publish"), true),
                    workingRoot.get("code").in(0, -50),
                    criteriaBuilder.equal(workingRoot.get("sendReply"), true)
            );
            workingQuery.where(criteriaBuilder.not(isArchivedForWorking));
            Long workingCount = entityManager.createQuery(workingQuery).getSingleResult();
            result.put("workingCount", workingCount);

            // 计算“已归档”的稿件数量
            CriteriaQuery<Long> archivedQuery = criteriaBuilder.createQuery(Long.class);
            Root<RecordHistory> archivedRoot = archivedQuery.from(RecordHistory.class);
            archivedQuery.select(criteriaBuilder.count(archivedRoot));
            
            // 重新构建归档状态的判断条件（用于已归档查询）
            Predicate isArchivedForArchived = criteriaBuilder.and(
                    criteriaBuilder.equal(archivedRoot.get("publish"), true),
                    archivedRoot.get("code").in(0, -50),
                    criteriaBuilder.equal(archivedRoot.get("sendReply"), true)
            );
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
    public Map<String, String> delete(@PathVariable("id") Long id) {
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
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                String filePath = part.getFilePath();
                if(! filePath.startsWith(workPath)){
                    part.setFileDelete(true);
                    part = partRepository.save(part);
                    continue;
                }
                String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                File startDir = new File(startDirPath);
                File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
                part.setFileDelete(true);
                partRepository.save(part);
            }
            partRepository.deleteAll(partList);
            historyRepository.delete(history);
            result.put("type", "success");
            result.put("msg", "录制历史删除成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "录制历史不存在");
            return result;
        }
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
            historyRepository.save(history);
            List<RecordHistoryPart> partList = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            for (RecordHistoryPart part : partList) {
                part.setUpload(false);
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
            // 1. 已发布 (publish = true) 且 稿件状态正常 (code 为 0 或 -50) 且 弹幕发送完成 (sendReply = true)
            // 2. 或者：设置为不上传 (upload = false) 且 录制已结束 (不存在正在录制的分P)

            // 正常上传并完成的条件
            Predicate isPublishedArchived = criteriaBuilder.and(
                    criteriaBuilder.equal(root.get("publish"), true),
                    root.get("code").in(0, -50),
                    criteriaBuilder.equal(root.get("sendReply"), true)
            );

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

            // 综合归档状态
            Predicate isArchived = criteriaBuilder.or(isPublishedArchived, isNoUploadArchived);

            if ("working".equals(request.getViewType())) {
                // 工作中 = 非归档
                predicatesList.add(criteriaBuilder.not(isArchived));
            } else if ("archived".equals(request.getViewType())) {
                predicatesList.add(isArchived);
            }
        }

        return predicatesList;
    }

    /**
     * 填充RecordHistory的额外字段，包括分P统计、放弃分P信息等。
     * 注意：房间名(roomName)需要调用方单独设置，通常通过roomCache获取。
     * 此方法用于确保返回给前端的数据一致性。
     */
    private void populateHistoryFields(RecordHistory history) {
        if (history == null) {
            return;
        }
        // 分P总数
        history.setPartCount(partRepository.countByHistoryId(history.getId()));
        // 分P总时长
        history.setPartDuration(partRepository.sumHistoryDurationByHistoryId(history.getId()));
        // 已上传分P数
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
        }

    // 计算是否处于等待投稿状态
    boolean waitingForPublish = false;
    if (history.isUpload() && !history.isPublish() && !history.isRecording() 
            && history.getGiveUpPartCount() == 0 && history.getPartCount() > 0
            && history.getUploadPartCount() == history.getPartCount() && history.getEndTime() != null) {
        
        // 获取合并间隔时间配置，参考 publishJob 中的范围校验（1-1440分钟）
        String mergeIntervalConfig = systemConfigService.getAllConfigsMap()
                .get(top.sshh.bililiverecoder.service.SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
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
