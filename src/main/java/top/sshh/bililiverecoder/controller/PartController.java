package top.sshh.bililiverecoder.controller;


import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.lifecycle.ShutdownState;
import top.sshh.bililiverecoder.service.RecordPartUploadService;
import top.sshh.bililiverecoder.service.UploadServiceFactory;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.bili.Cookie;
import top.sshh.bililiverecoder.util.bili.WebCookie;
import top.sshh.bililiverecoder.util.bili.upload.EdtiorSpaceRequest;
import top.sshh.bililiverecoder.util.bili.upload.pojo.EditorSpaceBean;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/part")
public class PartController {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private BiliUserRepository userRepository;
    @Autowired
    private RecordRoomRepository roomRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private UploadServiceFactory uploadServiceFactory;
    @Autowired
    private ShutdownState shutdownState;

    @Resource(name = "editorBilibiliUploadService")
    private RecordPartUploadService editPartUploadService;

    @PostConstruct
    public void initWorkPath() {
        workPath = workPath.replaceAll("\\\\\\\\", "\\\\");
        workPath = workPath.replace("\\", "/");
    }

    @PostMapping("/list/{id}")
    public List<RecordHistoryPart> list(@PathVariable("id") Long id) {
        return partRepository.findByHistoryIdOrderByStartTimeAsc(id);
    }

    @PostMapping("/list2/{id}")
    public Map<String, Object> list2(@PathVariable("id") Long id) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<RecordHistoryPart> parts = partRepository.findByHistoryIdOrderByStartTimeAsc(id);
        Optional<RecordHistory> histOpt = historyRepository.findById(id);
        boolean historyPublished = histOpt.isPresent() && histOpt.get().isPublish();
        List<Map<String, Object>> items = new ArrayList<>();
        int blocking = 0;
        long nowMs = System.currentTimeMillis();
        long stableThresholdMs = 10L * 60L * 1000L;

        for (RecordHistoryPart p : parts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("roomId", p.getRoomId());
            m.put("historyId", p.getHistoryId());
            m.put("page", p.getPage());
            m.put("title", p.getTitle());
            m.put("fileName", p.getFileName());
            m.put("filePath", p.getFilePath());
            m.put("fileSize", p.getFileSize());
            m.put("duration", p.getDuration());
            m.put("recording", p.isRecording());
            m.put("upload", p.isUpload());
            m.put("startTime", p.getStartTime());
            m.put("endTime", p.getEndTime());
            m.put("uploadRetryCount", p.getUploadRetryCount());
            m.put("deleteFailType", p.getDeleteFailType());
            m.put("deleteFailReason", p.getDeleteFailReason());

            String issueCode = null;
            String issueMessage = null;
            boolean actionable = false;
            boolean blockingIssue = false;
            List<String> actions = new ArrayList<>();

            if (!isBlank(p.getDeleteFailType()) || p.getUploadRetryCount() >= 9999) {
                issueCode = isBlank(p.getDeleteFailType()) ? "GIVE_UP" : p.getDeleteFailType();
                issueMessage = isBlank(p.getDeleteFailReason()) ? "该分P已被标记为跳过/放弃上传" : p.getDeleteFailReason();
                actionable = "SKIPPED_THRESHOLD".equals(issueCode) || "MANUAL_SKIP".equals(issueCode);
                if (actionable && !historyPublished) {
                    actions.add("BIND_FILE");
                    actions.add("MARK_FINISHED");
                }
            } else if (!historyPublished) {
                String fp = p.getFilePath();
                File f = fp == null ? null : new File(fp);
                boolean fileExists = f != null && f.exists();
                boolean fileStable = fileExists && f.lastModified() > 0 && f.lastModified() < (nowMs - stableThresholdMs);

                if (p.isRecording() || p.getEndTime() == null) {
                    if (fileExists && fileStable) {
                        issueCode = "MISSING_CLOSE";
                        issueMessage = "疑似遗漏文件关闭事件：文件已稳定但仍显示录制中";
                        actionable = true;
                        blockingIssue = true;
                        actions.add("RESCAN");
                        actions.add("MARK_FINISHED");
                    }
                } else if (!fileExists) {
                    issueCode = "FILE_MISSING";
                    issueMessage = "分P文件不存在或路径为空";
                    actionable = true;
                    blockingIssue = true;
                    actions.add("BIND_FILE");
                    actions.add("MARK_FINISHED");
                }
            }

            if (blockingIssue) {
                blocking++;
            }

            m.put("issueCode", issueCode);
            m.put("issueMessage", issueMessage);
            m.put("actionable", actionable);
            m.put("blocking", blockingIssue);
            m.put("actions", actions);
            items.add(m);
        }

        items.sort(Comparator.comparingInt(o -> {
            Object page = o.get("page");
            if (page instanceof Number) {
                return ((Number) page).intValue();
            }
            return 0;
        }));

        resp.put("items", items);
        resp.put("hasBlockingIssues", blocking > 0);
        resp.put("blockingIssueCount", blocking);
        return resp;
    }

    @PostMapping("/rescan/{id}")
    public Map<String, Object> rescan(@PathVariable("id") Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "分P不存在");
            return result;
        }
        RecordHistoryPart part = partOptional.get();
        if (isBlank(part.getFilePath())) {
            result.put("type", "warning");
            result.put("msg", "分P文件路径为空，请先补全文件");
            return result;
        }
        File file = new File(part.getFilePath());
        if (!file.exists()) {
            result.put("type", "warning");
            result.put("msg", "分P文件不存在，请先补全文件");
            return result;
        }
        long stableThresholdMs = 10L * 60L * 1000L;
        long nowMs = System.currentTimeMillis();
        if (file.lastModified() > nowMs - stableThresholdMs) {
            result.put("type", "info");
            result.put("msg", "文件可能仍在写入，稍后再试");
            return result;
        }
        boolean changed = false;
        if (part.isRecording()) {
            part.setRecording(false);
            changed = true;
        }
        if (part.getEndTime() == null) {
            part.setEndTime(LocalDateTime.now());
            changed = true;
        }
        long size = file.length();
        if (size > 0 && part.getFileSize() != size) {
            part.setFileSize(size);
            changed = true;
        }
        if (changed) {
            partRepository.save(part);
        }

        boolean triggered = false;
        Optional<RecordHistory> historyOptional = part.getHistoryId() == null ? Optional.empty() : historyRepository.findById(part.getHistoryId());
        if (!shutdownState.isShuttingDown() && historyOptional.isPresent()
                && historyOptional.get().isUpload() && !historyOptional.get().isPublish()) {
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
            if (room != null) {
                try {
                    uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                    triggered = true;
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("PartRepair.Rescan.UploadTriggerFailed")
                            .add("partId", part.getId())
                            .add("historyId", part.getHistoryId())
                            .add("roomId", part.getRoomId())
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            }
        }

        log.info("[BLR] {}", LogKvs.event("PartRepair.Rescan.Done")
                .add("partId", part.getId())
                .add("historyId", part.getHistoryId())
                .add("roomId", part.getRoomId())
                .add("triggerUpload", triggered));

        result.put("type", "success");
        result.put("msg", triggered ? "已重试扫描并触发上传" : "已重试扫描并修正状态");
        return result;
    }

    @PostMapping("/markFinished/{id}")
    public Map<String, Object> markFinished(@PathVariable("id") Long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "分P不存在");
            return result;
        }
        RecordHistoryPart part = partOptional.get();
        // 已投稿稿件不允许修改分P状态
        if (part.getHistoryId() != null) {
            Optional<RecordHistory> histOpt = historyRepository.findById(part.getHistoryId());
            if (histOpt.isPresent() && histOpt.get().isPublish()) {
                result.put("type", "warning");
                result.put("msg", "该稿件已投稿，不允许修改分P状态");
                return result;
            }
        }
        if (part.isRecording()) {
            part.setRecording(false);
        }
        if (part.getEndTime() == null) {
            part.setEndTime(LocalDateTime.now());
        }
        if (part.getFilePath() != null) {
            try {
                File f = new File(part.getFilePath());
                if (f.exists() && part.getFileSize() <= 0) {
                    part.setFileSize(f.length());
                }
            } catch (Exception ignored) {
            }
        }
        part.setUpload(false);
        part.setUploadRetryCount(9999);
        part.setDeleteFailType("MANUAL_SKIP");
        if (isBlank(part.getDeleteFailReason())) {
            part.setDeleteFailReason("用户已标记该分P结束/跳过，允许稿件继续推进");
        }
        partRepository.save(part);

        log.info("[BLR] {}", LogKvs.event("PartRepair.MarkFinished")
                .add("partId", part.getId())
                .add("historyId", part.getHistoryId())
                .add("roomId", part.getRoomId()));

        result.put("type", "success");
        result.put("msg", "已标记为结束，稿件可继续推进");
        return result;
    }

    @PostMapping("/bindFile/{id}")
    public Map<String, Object> bindFile(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        Map<String, Object> result = new LinkedHashMap<>();
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isEmpty()) {
            result.put("type", "warning");
            result.put("msg", "分P不存在");
            return result;
        }
        RecordHistoryPart part = partOptional.get();
        // 已投稿稿件不允许补全文件
        if (part.getHistoryId() != null) {
            Optional<RecordHistory> histOpt = historyRepository.findById(part.getHistoryId());
            if (histOpt.isPresent() && histOpt.get().isPublish()) {
                result.put("type", "warning");
                result.put("msg", "该稿件已投稿，不允许补全文件");
                return result;
            }
        }
        String filePath = body == null ? null : String.valueOf(body.get("filePath"));
        if (filePath != null) {
            filePath = filePath.replace("\\", "/");
        }
        boolean triggerUpload = body != null && Boolean.TRUE.equals(body.get("triggerUpload"));
        if (isBlank(filePath)) {
            result.put("type", "warning");
            result.put("msg", "请选择文件");
            return result;
        }
        if (!isUnderWorkPath(filePath)) {
            result.put("type", "warning");
            result.put("msg", "文件不在工作目录下，已拒绝");
            return result;
        }
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            result.put("type", "warning");
            result.put("msg", "文件不存在");
            return result;
        }

        part.setFilePath(filePath);
        part.setFileSize(file.length());
        if (part.isRecording()) {
            part.setRecording(false);
        }
        if (part.getEndTime() == null) {
            part.setEndTime(LocalDateTime.now());
        }
        part.setUpload(false);
        part.setUploadRetryCount(0);
        part.setDeleteFailType(null);
        part.setDeleteFailReason(null);
        partRepository.save(part);

        boolean triggered = false;
        Optional<RecordHistory> historyOptional = part.getHistoryId() == null ? Optional.empty() : historyRepository.findById(part.getHistoryId());
        if (triggerUpload && !shutdownState.isShuttingDown() && historyOptional.isPresent() && historyOptional.get().isUpload()) {
            RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
            if (room != null) {
                try {
                    uploadServiceFactory.getUploadService(room.getLine()).asyncUpload(part);
                    triggered = true;
                } catch (Exception e) {
                    log.error("[BLR] {}", LogKvs.event("PartRepair.BindFile.UploadTriggerFailed")
                            .add("partId", part.getId())
                            .add("historyId", part.getHistoryId())
                            .add("roomId", part.getRoomId())
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            }
        }

        log.info("[BLR] {}", LogKvs.event("PartRepair.BindFile.Done")
                .add("partId", part.getId())
                .add("historyId", part.getHistoryId())
                .add("roomId", part.getRoomId())
                .add("triggerUpload", triggered));

        result.put("type", "success");
        result.put("msg", triggered ? "已补全文件并触发上传" : "已补全文件");
        return result;
    }

    private boolean isUnderWorkPath(String filePath) {
        try {
            String normalizedWork = (workPath.endsWith("/") ? workPath : (workPath + "/")).replace("\\", "/");
            String fp = filePath.replace("\\", "/");
            return fp.toLowerCase(java.util.Locale.ROOT).startsWith(normalizedWork.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }



    @GetMapping("/uploadEditor/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入id");
            return result;
        }
        Optional<RecordHistoryPart> partOptional = partRepository.findById(id);
        if (partOptional.isPresent()) {
            RecordHistoryPart part = partOptional.get();
            String filePath = part.getFilePath();
            File file = new File(filePath);
            if(file.exists()){
                RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
                if(room.getUploadUserId() == null){
                    result.put("type", "warning");
                    result.put("msg", "没有配置上传用户");
                    return result;
                }
                Optional<BiliBiliUser> userOptional = userRepository.findById(room.getUploadUserId());
                if(userOptional.isEmpty()){
                    result.put("type", "warning");
                    result.put("msg", "没有配置上传用户");
                    return result;
                }
                BiliBiliUser user = userOptional.get();
                WebCookie cookie = Cookie.parse(user.getCookies());
                EdtiorSpaceRequest edtiorSpaceRequest = new EdtiorSpaceRequest(cookie);
                try {
                    EditorSpaceBean spaceBean = edtiorSpaceRequest.getPojo();
                    EditorSpaceBean.Data data = spaceBean.getData();
                    long freeSize = data.getTotal() - data.getUsed();
                    if(freeSize<file.length()){
                        result.put("type", "warning");
                        result.put("msg", "云剪辑剩余空间不足，剩余"+freeSize/1024/1024+"Mb"+",文件大小为"+file.length()/1024/1024+"Mb");
                        return result;
                    }
                    editPartUploadService.asyncUpload(part);
                    result.put("type", "success");
                    result.put("msg", "云剪辑上传开始，剩余空间"+freeSize/1024/1024+"Mb"+",文件大小为"+file.length()/1024/1024+"Mb");
                    return result;

                } catch (HttpException e) {
                    result.put("type", "warning");
                    result.put("msg", "查询云剪辑剩余空间发生错误");
                    return result;
                }


            }else {
                result.put("type", "warning");
                result.put("msg", "分p文件不存在");
                return result;
            }
        } else {
            result.put("type", "warning");
            result.put("msg", "分p不存在");
            return result;
        }
    }
}
