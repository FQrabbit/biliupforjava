package top.sshh.bililiverecoder.service;

import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.RecordHistory;

/** 手动操作只在对应的处理阶段开放 */
public final class HistoryOperationPolicy {
    private HistoryOperationPolicy() {}

    public static String disabledReason(RecordHistory history, String action) {
        if (history == null) return "稿件不存在，请刷新列表";
        if (history.isDeletePending()) return "稿件正在等待或执行删除，请先取消尚未执行的删除";
        if (history.isProcessingArchived()) return "稿件已强制归档，请先恢复处理";
        boolean recording = history.isRecording() || history.isStreaming() || history.getRecordPartCount() > 0;
        boolean online = history.isPublish() && history.getBvId() != null && !history.getBvId().isBlank();
        boolean ordinaryBusy = history.getPublishTasks() != null && history.getPublishTasks().stream()
                .anyMatch(task -> task.getOperation() != PublishTaskOperation.HIGH_ENERGY
                        && task.getState() != null && task.getState().isActive());
        if ("updatePartStatus".equals(action)) {
            return recording ? "" : "当前没有仍在录制的分P，无需结束录制状态";
        }
        if ("highEnergyCutPublish".equals(action)) {
            if (recording) return "请等录制结束后再生成高能片段";
            if (history.getPartCount() <= 0) return "当前没有可用于剪辑的分P";
            if (history.getPublishTasks() != null && history.getPublishTasks().stream()
                    .anyMatch(task -> task.getOperation() == PublishTaskOperation.HIGH_ENERGY
                            && task.getState() != null && task.getState().isActive())) return "高能片段任务已在处理中";
            return "";
        }
        if (ordinaryBusy) return "稿件已有未结束的投稿或编辑任务，请先处理或取消该任务";
        if ("updatePublishStatus".equals(action)) {
            if (recording) return "录制中不能重置状态，请先确认录制已结束";
            if (history.isPublish() || nonBlank(history.getBvId()) || nonBlank(history.getAvId())) {
                return "已投稿稿件不能重置成新稿件，请使用编辑分P或转码修复";
            }
            return history.getPartCount() > 0 ? "" : "当前没有可重置的分P";
        }
        if ("rePublish".equals(action)) {
            if (recording) return "请等录制结束后再执行转码修复";
            boolean issue = history.getCode() == -20 || "TIMESTAMP_JUMP".equals(history.getPublishIssueType())
                    || (history.getGiveUpPartTypes() != null && history.getGiveUpPartTypes().contains("TIMESTAMP_JUMP"));
            return issue ? "" : "当前没有检测到转码失败或时间戳跳变，无需转码修复";
        }
        if (!online) return "请先完成稿件投稿并取得 BVID，再操作弹幕队列";
        if (history.getCode() != 0 && history.getCode() != -50) return "稿件尚未审核通过或当前不可发送，请先处理平台状态";
        if ("deleteHistoryMsg".equals(action)) return history.getMsgCount() > 0 ? "" : "当前没有可删除的弹幕记录";
        boolean ordinaryEnabled = history.getCode() == 0 && Boolean.TRUE.equals(history.getRoomSendDm());
        boolean advancedEnabled = Boolean.TRUE.equals(history.getRoomSendSc());
        boolean dmEnabled = ordinaryEnabled || advancedEnabled;
        boolean replyEnabled = Boolean.TRUE.equals(history.getRoomSendSc()) || Boolean.TRUE.equals(history.getRoomSendGiftReply());
        if ("reloadHistoryMsg".equals(action)) {
            if (!dmEnabled && !replyEnabled) return "当前房间未开启可用的弹幕或评论发送功能";
            return history.getPartCount() > 0 ? "" : "当前没有可重新读取弹幕的分P";
        }
        if ("retryFailedDanmaku".equals(action)) {
            if (!dmEnabled) return "当前房间未开启稿件可用的弹幕发送功能";
            return history.getFailedMsgCount() > 0 ? "" : "当前没有未成功的弹幕需要重试";
        }
        if ("abandonHistoryMsgQueue".equals(action)) {
            boolean pending = (ordinaryEnabled && history.getPendingNormalMsgCount() > 0)
                    || (advancedEnabled && history.getPendingHighMsgCount() > 0);
            boolean replyPending = replyEnabled && !history.isSendReply()
                    && (history.getPendingHighMsgCount() > 0 || history.getAdvancedMsgCount() > 0
                    || history.getHighReplyLineCount() > 0 || history.getGiftReplyLineCount() > 0);
            return pending || replyPending ? "" : "当前没有可放弃的待发送弹幕或评论";
        }
        return "";
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
}
