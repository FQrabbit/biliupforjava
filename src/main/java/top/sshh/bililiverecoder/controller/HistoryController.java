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
            history.setPartCount(partRepository.countByHistoryId(history.getId()));
            history.setPartDuration(partRepository.sumHistoryDurationByHistoryId(history.getId()));
            history.setUploadPartCount(partRepository.countByHistoryIdAndFileNameNotNull(history.getId()));

            // 标记“永久放弃上传”的分P，便于 UI 提示用户具体哪个文件异常
            int giveUpCount = 0;
            try {
                giveUpCount = partRepository.countGiveUpPartsByHistoryId(history.getId());
            } catch (Exception ignored) {
            }
            history.setGiveUpPartCount(giveUpCount);
            if (giveUpCount > 0) {
                try {
                    history.setGiveUpPartFiles(partRepository.findGiveUpPartFilePathsByHistoryId(history.getId()));
                } catch (Exception ignored) {
                }
            }

            // 注意：这里不要在列表接口里落库纠偏 recording。
            // 原因：历史数据可能处于“分P切片/短暂事件延迟”的中间态，
            // 若在此处把 recording 误改为 false，会让发布定时任务误判并触发投稿。
            // 列表展示层面仅基于分P实际状态计算。
            int actuallyRecordingParts = partRepository.countActuallyRecordingPartsByHistoryId(history.getId());
            history.setRecordPartCount(actuallyRecordingParts);
            history.setRecording(actuallyRecordingParts > 0);

            if (StringUtils.isNotBlank(history.getBvId())) {
                history.setMsgCount(msgRepository.countByBvid(history.getBvId()));
                history.setSuccessMsgCount(msgRepository.countByBvidAndCode(history.getBvId(), 0));
            }
        }
        Map<String,Object> result = new HashMap<>();
        result.put("data",list);
        result.put("total",total);
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
        return predicatesList;
    }
}
