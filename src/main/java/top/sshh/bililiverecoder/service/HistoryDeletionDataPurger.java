package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.HistoryDeletionTaskRepository;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class HistoryDeletionDataPurger {
    private final HistoryDeletionTaskRepository tasks;
    private final RecordHistoryRepository histories;
    private final RecordHistoryPartRepository parts;
    private final LiveMsgRepository messages;
    private final PartFileOperationService partFileOperations;
    private final RoomLiveEventXmlIssueService xmlIssues;
    private final VideoCommentTaskService comments;
    private final VideoVisibilityRestoreService visibility;

    public HistoryDeletionDataPurger(HistoryDeletionTaskRepository tasks, RecordHistoryRepository histories,
                                     RecordHistoryPartRepository parts, LiveMsgRepository messages,
                                     PartFileOperationService partFileOperations,
                                     RoomLiveEventXmlIssueService xmlIssues,
                                     VideoCommentTaskService comments,
                                     VideoVisibilityRestoreService visibility) {
        this.tasks = tasks;
        this.histories = histories;
        this.parts = parts;
        this.messages = messages;
        this.partFileOperations = partFileOperations;
        this.xmlIssues = xmlIssues;
        this.comments = comments;
        this.visibility = visibility;
    }

    @Transactional
    public PurgeResult purge(Long taskId, Long historyId) {
        HistoryDeletionTask task = tasks.findByIdForUpdate(taskId).orElse(null);
        if (task == null || !"RUNNING".equals(task.getState())) return new PurgeResult(0, 0);
        RecordHistory history = histories.findByIdForUpdate(historyId).orElse(null);
        if (history == null) {
            task.setState("COMPLETED");
            task.setNextAttemptAt(null);
            task.setLastError(null);
            task.setUpdatedAt(LocalDateTime.now());
            tasks.save(task);
            return new PurgeResult(0, 0);
        }
        List<RecordHistoryPart> historyParts = parts.findByHistoryIdOrderByStartTimeAsc(historyId);
        int deletedMessages = messages.deleteByHistoryId(historyId);
        xmlIssues.deleteByHistoryId(historyId);
        for (RecordHistoryPart part : historyParts) partFileOperations.purgeMetadata(part.getId());
        int deletedParts = parts.deleteByHistoryId(historyId);
        histories.delete(history);
        comments.deleteForHistory(historyId);
        visibility.deleteCompletedForHistory(historyId);

        task.setState("COMPLETED");
        task.setNextAttemptAt(null);
        task.setLastError(null);
        task.setUpdatedAt(LocalDateTime.now());
        tasks.save(task);
        return new PurgeResult(deletedMessages, deletedParts);
    }

    public record PurgeResult(int deletedMessages, int deletedParts) {}
}
