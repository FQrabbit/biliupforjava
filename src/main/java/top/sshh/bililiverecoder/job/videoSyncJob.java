package top.sshh.bililiverecoder.job;

import com.alibaba.fastjson.JSON;
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
import top.sshh.bililiverecoder.service.impl.RecordBiliPublishService;
import top.sshh.bililiverecoder.util.BiliApi;

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
    private RecordBiliPublishService publishService;

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
                e.printStackTrace();
            }
            syncOne(next);
        }

    }

    public void syncOne(RecordHistory next) {
        RecordRoom room = roomRepository.findByRoomId(next.getRoomId());
        if (room == null) {
            log.error("同步视频状态，未找到房间{}，请删除该录制历史 {}", next.getRoomId(), next);
            return;
        }
        BiliBiliUser user = null;
        if(room.getUploadUserId() != null){
            user = userRepository.findById(room.getUploadUserId()).orElse(null);
        }

        BiliVideoInfoResponse videoInfoResponse = BiliApi.getVideoInfo(user,next.getBvId());
        int code = videoInfoResponse.getCode();
        if(code != 0){
            log.debug("获取视频信息失败 标题:{} bvid:{} code:{} msg:{}", next.getTitle(), next.getBvId(), code, videoInfoResponse.getMessage());
            
            // 处理 62002 (稿件不可见)
            if (code == 62002) {
                next.setCode(code);
                historyRepository.save(next);
                log.info("稿件不可见 (code 62002), 停止同步");
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
                        log.warn("视频已确认删除 (Member API 404), 更新状态为 -404");
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
                            log.info("二次确认稿件状态成功(view API): bvid={} state={}", next.getBvId(), state);
                            return;
                        }

                        int oldCode = next.getCode();
                        if (room.getIsOnlySelf() == 1) {
                            // 保守策略：房间配置要求仅自己可见，但当前无法可靠读取状态时，避免误发普通弹幕。
                            next.setCode(-50);
                            historyRepository.save(next);
                            log.warn("无法确认稿件状态，按房间配置仅自己可见处理: bvid={} oldCode={}", next.getBvId(), oldCode);
                            return;
                        }

                        historyRepository.save(next);
                        log.warn("无法确认稿件状态，保持原状态: bvid={} oldCode={}", next.getBvId(), oldCode);
                        return;
                    } else {
                        log.warn("Member API 返回 code {}, 暂不标记为删除", partInfo.getCode());
                    }
                } else {
                    log.warn("未配置上传用户，无法确认 404 是否为权限问题，跳过状态更新");
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
                part.setCid(page.getCid());
                part.setPage(page.getPage());
                part.setDuration(page.getDuration());
                part = partRepository.save(part);
                //解析弹幕入库
                List<LiveMsg> liveMsgs = msgRepository.queryByPartId(part.getId());
                msgRepository.deleteAll(liveMsgs);
                liveMsgService.processing(part);
                log.info("同步视频分p 成功==>{}", JSON.toJSONString(part));
            }
        }
        for (BiliVideoInfoResponse.BiliVideoInfoPart page : pages) {
            RecordHistoryPart part = partRepository.findByHistoryIdAndTitle(next.getId(), page.getPart());
            if (part != null) {
                //如果配置成发布完成后删除则删除文件
                String filePath = part.getFilePath();
                if (recordRoom != null && recordRoom.getDeleteType() == 2) {
                    File file = new File(filePath);
                    boolean delete = file.delete();
                    if (delete) {
                        log.info("{}=>文件删除成功！！！", filePath);
                    } else {
                        log.error("{}=>文件删除失败！！！", filePath);
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
                                log.info("{}=>文件移动成功！！！", file.getName());
                            } catch (Exception e) {
                                log.error("{}=>文件移动失败！！！", file.getName());
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
                                log.info("{}=>文件复制成功！！！", file.getName());
                            } catch (Exception e) {
                                log.error("{}=>文件复制失败！！！", file.getName());
                            }
                        }
                    }
                    part = partRepository.save(part);
                }
            }
        }
    }
}
