package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.BiliLiveRoomInfoResponse;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@Component
public class RoomStatusSyncJob {

    @Autowired
    private RecordRoomRepository roomRepository;

    // 启动后10秒执行一次，之后每隔60分钟执行一次
    @Scheduled(fixedDelay = 3600000, initialDelay = 10000)
    public void syncRoomStatus() {
        log.info("[BLR] {}", LogKvs.event("RoomStatusSyncJob.Start"));
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
                        log.info("[BLR] {}", LogKvs.event("RoomStatusSyncJob.StreamingChanged")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname())
                                .add("streaming", isLive));
                    }

                    // 如果直播结束，强制设置录制状态为false，防止状态卡死
                    // 注意：如果直播中，我们不强制设置录制为true，因为录制可能还没开始或失败
                    if (!isLive && room.isRecording()) {
                        room.setRecording(false);
                        changed = true;
                        log.info("[BLR] {}", LogKvs.event("RoomStatusSyncJob.ForceResetRecording")
                                .add("roomId", room.getRoomId())
                                .add("uname", room.getUname()));
                    }
                    
                    // 更新标题，方便查看
                    if(response.getData().getTitle() != null && !response.getData().getTitle().equals(room.getTitle())){
                         room.setTitle(response.getData().getTitle());
                         changed = true;
                        log.debug("[BLR] {}", LogKvs.event("RoomStatusSyncJob.TitleChanged")
                               .add("roomId", room.getRoomId())
                               .add("uname", room.getUname()));
                    }

                    if (changed) {
                        roomRepository.save(room);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[BLR] {}", LogKvs.event("RoomStatusSyncJob.SleepInterrupted")
                        .add("waitMs", 10000)
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname()), e);
                break;
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("RoomStatusSyncJob.Failed")
                        .add("roomId", room.getRoomId())
                        .add("uname", room.getUname())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
        }
        log.info("[BLR] {}", LogKvs.event("RoomStatusSyncJob.Done"));
    }
}
