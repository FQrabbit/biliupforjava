package top.sshh.bililiverecoder.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.data.BiliLiveRoomInfoResponse;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
public class RoomStatusSyncJob {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;

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
                    if (!isLive) {
                        if (room.isRecording() || (room.getHistoryId() != null && room.getHistoryId() != -1)) {
                            room.setRecording(false);
                            // 同时清理关联的历史记录状态
                            if (room.getHistoryId() != null && room.getHistoryId() != -1) {
                                Optional<RecordHistory> historyOpt = historyRepository.findById(room.getHistoryId());
                                if (historyOpt.isPresent()) {
                                    RecordHistory history = historyOpt.get();
                                    if (!Objects.equals(history.getRoomId(), room.getRoomId())) {
                                        log.error("[BLR] {}", LogKvs.event("RoomStatusSyncJob.HistoryRoomMismatch")
                                                .add("roomId", room.getRoomId())
                                                .add("uname", room.getUname())
                                                .add("roomHistoryId", room.getHistoryId())
                                                .add("historyId", history.getId())
                                                .add("historyRoomId", history.getRoomId()));
                                        room.setHistoryId(-1L);
                                        changed = true;
                                    } else {
                                        if (history.isRecording() || history.isStreaming()) {
                                            history.setRecording(false);
                                            history.setStreaming(false);
                                            history.setEndTime(LocalDateTime.now());
                                            historyRepository.save(history);

                                            log.info("[BLR] {}", LogKvs.event("RoomStatusSyncJob.ForceResetHistory")
                                                    .add("roomId", room.getRoomId())
                                                    .add("historyId", history.getId()));
                                        }
                                    }
                                }
                            }
                            changed = true;
                            log.info("[BLR] {}", LogKvs.event("RoomStatusSyncJob.ForceResetRecording")
                                    .add("roomId", room.getRoomId())
                                    .add("uname", room.getUname()));
                        }
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
