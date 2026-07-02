package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
public class HistoryMsgRetryService {

    private static final int IN_BATCH_SIZE = 500;
    private static final List<Integer> NON_RETRYABLE_CODES = List.of(36701, 36702, 36714);

    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final LiveMsgRepository msgRepository;

    public HistoryMsgRetryService(RecordHistoryRepository historyRepository,
                                  RecordHistoryPartRepository partRepository,
                                  LiveMsgRepository msgRepository) {
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.msgRepository = msgRepository;
    }

    @Transactional
    public RetryResult retryFailedByHistoryId(Long historyId) {
        return retryFailedByHistoryId(historyId, 0);
    }

    @Transactional
    public RetryResult retryFailedByHistoryId(Long historyId, int displayedFailed) {
        if (historyId == null) {
            return RetryResult.warning("稿件不存在");
        }
        Optional<RecordHistory> historyOptional = historyRepository.findById(historyId);
        if (historyOptional.isEmpty()) {
            return RetryResult.warning("稿件不存在");
        }
        RecordHistory history = historyOptional.get();
        if (history.isForceArchived()) {
            return RetryResult.warning("稿件已强制归档，不能重新加入弹幕队列");
        }
        if (!history.isPublish() || (history.getCode() != 0 && history.getCode() != -50)) {
            return RetryResult.warning("当前稿件状态不允许重试弹幕");
        }

        List<Long> partIds = partRepository.findByHistoryId(historyId).stream()
                .map(RecordHistoryPart::getId)
                .filter(Objects::nonNull)
                .toList();
        if (partIds.isEmpty()) {
            return RetryResult.warning("当前稿件没有可检查的分P");
        }

        int totalFailed = countFailed(partIds, 0) + countFailed(partIds, 1);
        if (totalFailed <= 0) {
            int bvidFailed = countFailedByBvid(history.getBvId());
            RetryResult result = RetryResult.warning(buildNoRetryableMessage(displayedFailed, bvidFailed));
            result.displayedFailed = Math.max(0, displayedFailed);
            result.totalFailed = bvidFailed;
            result.skipped = Math.max(result.displayedFailed, bvidFailed);
            log.info("[BLR] {}", LogKvs.event("History.MsgRetry.NoDispatchableFailed")
                    .add("historyId", historyId)
                    .addIfNotBlank("bvid", history.getBvId())
                    .add("displayedFailed", displayedFailed)
                    .add("bvidFailed", bvidFailed));
            return result;
        }

        int ordinary = retryFailed(partIds, 0);
        int advanced = retryFailed(partIds, 1);
        int retried = ordinary + advanced;
        int skipped = Math.max(0, totalFailed - retried);

        RetryResult result = new RetryResult();
        result.displayedFailed = Math.max(0, displayedFailed);
        result.totalFailed = totalFailed;
        result.ordinary = ordinary;
        result.advanced = advanced;
        result.retried = retried;
        result.skipped = skipped;

        if (retried > 0) {
            result.success = true;
            result.type = "success";
            result.msg = skipped > 0
                    ? String.format("已将 %d 条未成功弹幕重新加入队列，跳过 %d 条暂不可重试项", retried, skipped)
                    : String.format("已将 %d 条未成功弹幕重新加入队列", retried);
            log.info("[BLR] {}", LogKvs.event("History.MsgRetry.Done")
                    .add("historyId", historyId)
                    .addIfNotBlank("bvid", history.getBvId())
                    .add("ordinary", ordinary)
                    .add("advanced", advanced)
                    .add("retried", retried)
                    .add("skipped", skipped));
        } else {
            result.success = false;
            result.type = "warning";
            result.msg = "当前未成功弹幕暂不可重试，可能是内容/时间非法，或稿件、房间、分P状态不满足发送条件";
            log.info("[BLR] {}", LogKvs.event("History.MsgRetry.None")
                    .add("historyId", historyId)
                    .addIfNotBlank("bvid", history.getBvId())
                    .add("totalFailed", totalFailed));
        }
        return result;
    }

    private int countFailed(List<Long> partIds, int pool) {
        long total = 0L;
        for (List<Long> batch : batches(partIds)) {
            total += msgRepository.countFailedByPartIdsAndPool(batch, pool);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private int countFailedByBvid(String bvid) {
        if (bvid == null || bvid.isBlank()) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, msgRepository.countFailedByBvid(bvid));
    }

    private String buildNoRetryableMessage(int displayedFailed, int bvidFailed) {
        if (displayedFailed > 0 || bvidFailed > 0) {
            return "当前未成功弹幕没有可重新入队的发送记录，通常是平台已明确拒绝、弹幕记录已被清理，或分P/CID、房间开关、稿件状态已不满足发送条件。可刷新列表确认最新统计。";
        }
        return "当前没有可重试的未成功弹幕";
    }

    private int retryFailed(List<Long> partIds, int pool) {
        int total = 0;
        for (List<Long> batch : batches(partIds)) {
            total += msgRepository.retryDispatchableFailedByPartIdsAndPool(batch, pool, NON_RETRYABLE_CODES);
        }
        return total;
    }

    private List<List<Long>> batches(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<List<Long>> batches = new ArrayList<>();
        for (int i = 0; i < values.size(); i += IN_BATCH_SIZE) {
            batches.add(values.subList(i, Math.min(i + IN_BATCH_SIZE, values.size())));
        }
        return batches;
    }

    public static class RetryResult {
        public boolean success;
        public String type;
        public String msg;
        public int totalFailed;
        public int displayedFailed;
        public int retried;
        public int skipped;
        public int ordinary;
        public int advanced;

        public static RetryResult warning(String msg) {
            RetryResult result = new RetryResult();
            result.success = false;
            result.type = "warning";
            result.msg = msg;
            return result;
        }

        public static RetryResult info(String msg) {
            RetryResult result = new RetryResult();
            result.success = true;
            result.type = "info";
            result.msg = msg;
            return result;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            map.put("type", type);
            map.put("msg", msg);
            map.put("totalFailed", totalFailed);
            map.put("displayedFailed", displayedFailed);
            map.put("retried", retried);
            map.put("skipped", skipped);
            map.put("ordinary", ordinary);
            map.put("advanced", advanced);
            return map;
        }
    }
}
