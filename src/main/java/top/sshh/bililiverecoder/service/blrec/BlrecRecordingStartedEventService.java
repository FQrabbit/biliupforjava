package top.sshh.bililiverecoder.service.blrec;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.entity.blrec.BlrecRoomInfoDTO;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Service("blrecRecordingStartedEventService")
public class BlrecRecordingStartedEventService implements BlrecEventService {

    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private top.sshh.bililiverecoder.service.SystemConfigService systemConfigService;

    @Override
    public void processing(BlrecEventDTO event) {
        LocalDateTime now = LocalDateTime.now();
        BlrecRoomInfoDTO roomInfo = event.getData().getRoomInfo();
        String roomId = roomInfo.getRoomId();

        RecordRoom room = roomRepository.findByRoomId(roomId);
        if (room == null) {
            room = new RecordRoom();
            room.setRoomId(roomId);
            room.setCreateTime(LocalDateTime.now());
            log.info("[BLR] {}", LogKvs.event("Blrec.Room.AutoCreate").add("roomId", roomId));
        }

        // 更新房间基本信息
        room.setUname(String.valueOf(roomInfo.getUid())); // 注意：blrec的room_info里没有uname，我们暂用uid代替
        room.setTitle(roomInfo.getTitle());
        room.setStreaming(roomInfo.getLiveStatus() == 1);
        room.setRecording(true); // 核心：将录制状态设置为 true
        
        // 尝试复用已有的历史记录（短时间开播合并逻辑）
        RecordHistory history = room.getHistoryId() != null ? historyRepository.findById(room.getHistoryId()).orElse(null) : null;
        if (history == null || history.isPublish() || !history.isRecording()) {
            // 从配置中读取合并时间间隔，默认20分钟
            int mergeIntervalMinutes = 20;
            try {
                String mergeIntervalConfig = systemConfigService.getAllConfigsMap().get(top.sshh.bililiverecoder.service.SystemConfigService.KEY_MERGE_INTERVAL_MINUTES);
                if (mergeIntervalConfig != null && !mergeIntervalConfig.isEmpty()) {
                    mergeIntervalMinutes = Integer.parseInt(mergeIntervalConfig);
                    if (mergeIntervalMinutes < 1) {
                        mergeIntervalMinutes = 1;
                    } else if (mergeIntervalMinutes > 1440) {
                        mergeIntervalMinutes = 1440;
                    }
                }
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("Blrec.RecordStarted.ParseMergeIntervalFailed")
                        .add("roomId", roomId)
                        .add("error", e.getMessage()));
            }
            // 查询在合并时间间隔内结束的记录
            List<RecordHistory> historyList = historyRepository.findByRoomIdAndEndTimeBetweenOrderByEndTimeAsc(roomId, now.minusMinutes((long) mergeIntervalMinutes), now);
            if (!CollectionUtils.isEmpty(historyList)) {
                // 过滤掉已经发布的稿件
                historyList = historyList.stream().filter(h -> !h.isPublish()).collect(java.util.stream.Collectors.toList());
                if (!historyList.isEmpty()) {
                    // 复用最近的一条记录（取列表的最后一条，即 EndTime 最大的）
                    history = historyList.get(historyList.size() - 1);
                } else {
                    // 如果所有记录都已发布，设置为null以便创建新记录
                    history = null;
                }
            }
        }
        
        // 如果没有可复用的历史记录，则创建新的
        if (history == null) {
            history = new RecordHistory();
            history.setRoomId(roomId);
            history.setTitle(roomInfo.getTitle());
            history.setStartTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(roomInfo.getLiveStartTime()), ZoneId.systemDefault()));
            history.setEndTime(history.getStartTime());
            history.setEventId(event.getId()); // 使用blrec的事件ID
            history.setRecording(true);
            history.setStreaming(true);
            history.setUpload(room.isUpload());
            history = historyRepository.save(history);
        } else {
            // 复用已有的历史记录
            history.setRecording(true);
            history.setStreaming(true);
            historyRepository.save(history);
            log.info("[BLR] {}", LogKvs.event("Blrec.RecordingStarted.ReuseHistory")
                    .add("roomId", roomId)
                    .add("title", roomInfo.getTitle())
                    .add("reusedHistoryId", history.getId()));
        }

        // 关联历史记录
        room.setHistoryId(history.getId());
        roomRepository.save(room);
    }
}
