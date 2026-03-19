package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.service.impl.LiveMsgService;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Slf4j
@Component
public class videoSyncJob {

    @Value("${record.work-path}")
    private String workPath;

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private LiveMsgService liveMsgService;
    @Autowired
    private LiveMsgRepository msgRepository;


    // 定时查询录制历史，每五分钟验证一下是否发布成功
    @Scheduled(fixedDelay = 300000, initialDelay = 5000)
    public void syncVideo() {
        //查询出所有需要同步的录播记录
        for (RecordHistory next : historyRepository.findSyncList()) {
            try {
                // 避免请求过快，每次请求间隔3秒
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                log.warn("[BLR] {}", LogKvs.event("VideoSync.SleepInterrupted")
                        .add("waitMs", 3000), e);
            }
            syncOne(next);
        }

    }

    public void syncOne(RecordHistory next) {
        syncOneInternal(next, true);
    }

    /**
     * 仅同步稿件状态/基础信息，不触发弹幕重解析与文件处理。
     * 用于前端“刷新状态”按钮，避免误删已有弹幕数据。
     */
    public void syncStatusOnly(RecordHistory next) {
        syncOneInternal(next, false);
    }

    private void syncOneInternal(RecordHistory next, boolean doPostPublishProcessing) {
        RecordRoom room = roomRepository.findByRoomId(next.getRoomId());
        if (room == null) {
            log.error("[BLR] {}", LogKvs.event("VideoSync.RoomMissing")
                    .add("roomId", next.getRoomId())
                    .add("historyId", next.getId())
                    .addIfNotBlank("bvid", next.getBvId())
                    .addIfNotBlank("title", next.getTitle()));
            return;
        }
        BiliBiliUser user = null;
        if(room.getUploadUserId() != null){
            user = userRepository.findById(room.getUploadUserId()).orElse(null);
        }

        BiliVideoInfoResponse videoInfoResponse = BiliApi.getVideoInfo(user,next.getBvId());
        int code = videoInfoResponse.getCode();
        if(code != 0){
            log.debug("[BLR] {}", LogKvs.event("VideoSync.VideoInfo.Failed")
                    .add("roomId", room.getRoomId())
                    .add("uname", room.getUname())
                    .add("historyId", next.getId())
                    .addIfNotBlank("title", next.getTitle())
                    .addIfNotBlank("bvid", next.getBvId())
                    .add("code", code)
                    .addIfNotBlank("msg", videoInfoResponse.getMessage()));
            
            // 处理 62002 (稿件不可见)
            if (code == 62002) {
                next.setCode(code);
                historyRepository.save(next);
                log.info("[BLR] {}", LogKvs.event("VideoSync.NotVisibleStop")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .add("historyId", next.getId())
                        .addIfNotBlank("bvid", next.getBvId())
                        .addIfNotBlank("title", next.getTitle())
                        .add("code", code));
                return;
            }

            if (code == -404) {
                if (user != null) {
                    // 尝试使用 Member API 二次确认
                    var partInfo = BiliApi.getVideoPartInfo(user, next.getBvId());
                    if (partInfo.getCode() == -404) {
                        // Member API 也返回 404，确认删除
                        next.setCode(code);
                        historyRepository.save(next);
                        log.warn("[BLR] {}", LogKvs.event("VideoSync.DeletedConfirmed")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", next.getId())
                                .addIfNotBlank("bvid", next.getBvId())
                                .addIfNotBlank("title", next.getTitle())
                                .add("code", code));
                    } else if (partInfo.getCode() == 0) {
                        // Member API 返回 0：注意其 state 字段语义不稳定，不能直接当作“可见性/审核状态”。
                        // 这里再用带 Cookie 的 view API 二次确认真实 state（0:公开, -50:仅自己可见）。
                        if (partInfo.getData() != null && partInfo.getData().getVideos() != null && !partInfo.getData().getVideos().isEmpty()) {
                            next.setAvId(String.valueOf(partInfo.getData().getVideos().get(0).getAid()));
                        }

                        try {
                            Thread.sleep(800);
                        } catch (InterruptedException ignored) {
                        }

                        BiliVideoInfoResponse confirm = BiliApi.getVideoInfo(user, next.getBvId());
                        if (confirm != null && confirm.getCode() == 0 && confirm.getData() != null) {
                            int state = confirm.getData().getState();
                            next.setCode(state);
                            next.setAvId(confirm.getData().getAid());
                            next.setBvId(confirm.getData().getBvid());
                            next.setCoverUrl(confirm.getData().getPic());
                            historyRepository.save(next);
                            log.info("[BLR] {}", LogKvs.event("VideoSync.Confirm.Success")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .addIfNotBlank("title", next.getTitle())
                                    .add("state", state));
                            return;
                        }

                        // 二次确认失败：补充 debug 信息，方便排查(例如 cookie 失效/风控/接口波动等)
                        if (confirm == null) {
                            log.debug("[BLR] {}", LogKvs.event("VideoSync.Confirm.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("reason", "confirm=null"));
                        } else {
                            log.debug("[BLR] {}", LogKvs.event("VideoSync.Confirm.Failed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("code", confirm.getCode())
                                    .addIfNotBlank("msg", confirm.getMessage()));
                        }

                        int oldCode = next.getCode();
                        if (room.getIsOnlySelf() == 1) {
                            // 保守策略：房间配置要求仅自己可见，但当前无法可靠读取状态时，避免误发普通弹幕。
                            next.setCode(-50);
                            historyRepository.save(next);
                            log.info("[BLR] {}", LogKvs.event("VideoSync.StateFallback.OnlySelfByRoomConfig")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("oldCode", oldCode)
                                    .add("newCode", -50));
                            return;
                        }

                        historyRepository.save(next);
                        log.info("[BLR] {}", LogKvs.event("VideoSync.StateFallback.KeepOld")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", next.getId())
                                .addIfNotBlank("bvid", next.getBvId())
                                .add("oldCode", oldCode));
                        return;
                    } else {
                        log.warn("[BLR] {}", LogKvs.event("VideoSync.MemberApi.Unexpected")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("historyId", next.getId())
                                .addIfNotBlank("bvid", next.getBvId())
                                .add("code", partInfo.getCode()));
                    }
                } else {
                    log.warn("[BLR] {}", LogKvs.event("VideoSync.Confirm.SkipNoUser")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", next.getId())
                            .addIfNotBlank("bvid", next.getBvId()));
                }
            }
            return;
        }
        BiliVideoInfoResponse.BiliVideoInfo videoInfoResponseData = videoInfoResponse.getData();
        // 更新状态
        next.setCode(videoInfoResponseData.getState());
        next.setAvId(videoInfoResponseData.getAid());
        next.setBvId(videoInfoResponseData.getBvid());
        next.setCoverUrl(videoInfoResponseData.getPic());
        next = historyRepository.save(next);

        // 前端“刷新状态”只需要更新状态，但如果发现分P缺失CID，我们还是得同步一下CID，否则弹幕发不出去
        List<RecordHistoryPart> dbParts = partRepository.findByHistoryId(next.getId());
        boolean hasMissingCid = dbParts.stream().anyMatch(p -> p.getCid() == null || p.getCid() == 0);
        if (!doPostPublishProcessing && !hasMissingCid) {
            return;
        }

        // 0: 开放浏览, -50: 仅自己可见
        // 这两种状态都视为"发布成功"，可以进行后续的弹幕解析
        if(videoInfoResponseData.getState() != 0 && videoInfoResponseData.getState() != -50){
            return;
        }
        
        RecordRoom recordRoom = room;
        List<BiliVideoInfoResponse.BiliVideoInfoPart> pages = videoInfoResponseData.getPages();
        for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
            RecordHistoryPart part = partRepository.findByHistoryIdAndTitle(next.getId(), page.getPart());
            if (part != null) {
                // 如果是手动刷新状态，仅针对缺失 CID 的分P进行解析
                boolean needReparse = part.getCid() == null || part.getCid() == 0;

                part.setCid(page.getCid());
                part.setPage(page.getPage());
                part.setDuration(page.getDuration());

                // 如果CID已恢复，且之前标记为异常，则清除异常状态
                if (part.getCid() != null && part.getCid() != 0 && part.getUploadRetryCount() >= 9999) {
                    part.setUploadRetryCount(0);
                    part.setUpload(true);
                    part.setDeleteFailReason("");
                    log.info("[BLR] {}", LogKvs.event("VideoSync.Part.ExceptionCleared")
                            .add("historyId", next.getId())
                            .add("partId", part.getId())
                            .add("msg", "CID已获取，清除异常状态"));
                }

                part = partRepository.save(part);

                if (doPostPublishProcessing || needReparse) {
                    //解析弹幕入库
                    List<LiveMsg> liveMsgs = msgRepository.queryByPartId(part.getId());
                    msgRepository.deleteAll(liveMsgs);
                    liveMsgService.processing(part);
                    log.info("[BLR] {}", LogKvs.event("VideoSync.PartSynced")
                            .add("roomId", room.getRoomId())
                            .add("uname", room.getUname())
                            .add("historyId", next.getId())
                            .addIfNotBlank("bvid", next.getBvId())
                            .add("partId", part.getId())
                            .add("page", part.getPage())
                            .add("cid", part.getCid())
                            .addIfNotBlank("partTitle", part.getTitle())
                            .add("durationSec", part.getDuration()));
                }
            }
        }
        // 只有在自动同步（发布后处理）流程中才考虑删除文件
        if (doPostPublishProcessing) {
            for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
                RecordHistoryPart part = partRepository.findByHistoryIdAndTitle(next.getId(), page.getPart());
                if (part != null) {
                    //如果配置成发布完成后删除则删除文件
                    String filePath = part.getFilePath();
                    if (recordRoom != null && recordRoom.getDeleteType() == 2) {
                        File file = new File(filePath);
                        boolean delete = file.delete();
                        if (delete) {
                            log.info("[BLR] {}", LogKvs.event("VideoSync.File.DeleteSuccess")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("partId", part.getId())
                                    .addIfNotBlank("filePath", filePath));
                        } else {
                            log.warn("[BLR] {}", LogKvs.event("VideoSync.File.DeleteFailed")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname())
                                    .add("historyId", next.getId())
                                    .addIfNotBlank("bvid", next.getBvId())
                                    .add("partId", part.getId())
                                    .addIfNotBlank("filePath", filePath));
                        }
                    } else if (recordRoom != null && StringUtils.isNotBlank(recordRoom.getMoveDir()) && recordRoom.getDeleteType() == 5) {
                        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                        String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                        String toDirPath = recordRoom.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                        File toDir = new File(toDirPath);
                        if (!toDir.exists()) {
                            toDir.mkdirs();
                        }
                        File startDir = new File(startDirPath);
                        File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                        if (files != null) {
                            for (File file : files) {
                                if (!filePath.startsWith(workPath)) {
                                    part.setFileDelete(true);
                                    part = partRepository.save(part);
                                    continue;
                                }
                                try {
                                    Files.move(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                    log.info("[BLR] {}", LogKvs.event("VideoSync.File.MoveSuccess")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName()));
                                } catch (Exception e) {
                                    log.error("[BLR] {}", LogKvs.event("VideoSync.File.MoveFailed")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName())
                                            .addIfNotBlank("err", e.getMessage())
                                            .add("ex", e.getClass().getSimpleName()), e);
                                }
                            }
                        }

                        part.setFilePath(toDirPath + filePath.substring(filePath.lastIndexOf("/") + 1));
                        part.setFileDelete(true);
                        part = partRepository.save(part);
                    } else if (recordRoom != null && StringUtils.isNotBlank(recordRoom.getMoveDir()) && recordRoom.getDeleteType() == 11) {
                        String fileName = filePath.substring(filePath.lastIndexOf("/") + 1, filePath.lastIndexOf("."));
                        String startDirPath = filePath.substring(0, filePath.lastIndexOf('/') + 1);
                        String toDirPath = recordRoom.getMoveDir() + filePath.substring(0, filePath.lastIndexOf('/') + 1).replace(workPath, "");
                        File toDir = new File(toDirPath);
                        if (!toDir.exists()) {
                            toDir.mkdirs();
                        }
                        File startDir = new File(startDirPath);
                        File[] files = startDir.listFiles((file, s) -> s.startsWith(fileName));
                        if (files != null) {
                            for (File file : files) {
                                try {
                                    Files.copy(Paths.get(file.getPath()), Paths.get(toDirPath + file.getName()),
                                            StandardCopyOption.REPLACE_EXISTING);
                                    log.info("[BLR] {}", LogKvs.event("VideoSync.File.CopySuccess")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName()));
                                } catch (Exception e) {
                                    log.error("[BLR] {}", LogKvs.event("VideoSync.File.CopyFailed")
                                            .add("roomId", room.getRoomId())
                                            .add("uname", room.getUname())
                                            .add("historyId", next.getId())
                                            .add("partId", part.getId())
                                            .add("from", file.getPath())
                                            .add("to", toDirPath + file.getName())
                                            .addIfNotBlank("err", e.getMessage())
                                            .add("ex", e.getClass().getSimpleName()), e);
                                }
                            }
                        }
                        part = partRepository.save(part);
                    }
                }
            }
        }
    }
}
