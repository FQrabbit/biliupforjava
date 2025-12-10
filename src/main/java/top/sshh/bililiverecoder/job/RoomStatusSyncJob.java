package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.BiliLiveRoomInfoResponse;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.BiliApi;

@Slf4j
@Component
public class RoomStatusSyncJob {

    @Autowired
    private RecordRoomRepository roomRepository;

    // 启动后10秒执行一次，之后每隔60分钟执行一次
    @Scheduled(fixedDelay = 3600000, initialDelay = 10000)
    public void syncRoomStatus() {
        log.info("开始执行直播间状态同步任务(兜底机制)...");
        for (RecordRoom room : roomRepository.findAll()) {
            try {
                // 避免请求过快，每10秒请求一个房间，降低API请求压力
                Thread.sleep(10000);
                BiliLiveRoomInfoResponse response = BiliApi.getLiveRoomInfo(room.getRoomId());
                if (response != null && response.getCode() == 0 && response.getData() != null) {
                    boolean isLive = response.getData().getLive_status() == 1;
                    boolean changed = false;

                    if (room.isStreaming() != isLive) {
                        room.setStreaming(isLive);
                        changed = true;
                        log.info("同步直播间状态: {} -> {}", room.getUname(), isLive ? "直播中" : "未直播");
                    }

                    // 如果直播结束，强制设置录制状态为false，防止状态卡死
                    // 注意：如果直播中，我们不强制设置录制为true，因为录制可能还没开始或失败
                    if (!isLive && room.isRecording()) {
                        room.setRecording(false);
                        changed = true;
                        log.info("同步直播间状态: {} 直播已结束，强制重置录制状态为未录制", room.getUname());
                    }
                    
                    // 更新标题，方便查看
                    if(response.getData().getTitle() != null && !response.getData().getTitle().equals(room.getTitle())){
                         room.setTitle(response.getData().getTitle());
                         changed = true;
                    }

                    if (changed) {
                        roomRepository.save(room);
                    }
                }
            } catch (Exception e) {
                log.error("同步直播间状态失败: {}", room.getRoomId(), e);
            }
        }
        log.info("直播间状态同步任务完成");
    }
}
