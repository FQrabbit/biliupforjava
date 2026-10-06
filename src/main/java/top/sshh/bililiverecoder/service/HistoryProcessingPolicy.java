package top.sshh.bililiverecoder.service;

import top.sshh.bililiverecoder.entity.RecordHistory;

/** 所有后台入口都通过同一条规则排除归档稿件 */
public final class HistoryProcessingPolicy {
    private HistoryProcessingPolicy() {}

    public static boolean blocksAutomatic(RecordHistory history) {
        return history == null || history.isDeletePending() || history.isProcessingArchived();
    }
}
