package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.util.LogKvs;

@Slf4j
@Service
public class PartFileCleanupPolicy {

    public enum CleanupMilestone {
        NONE, AFTER_UPLOAD, AFTER_AUDIT, AFTER_RECORD_CLOSE, SCHEDULED, AFTER_PUBLISH
    }

    @Autowired
    private RecordHistoryRepository historyRepository;

    public boolean shouldSkipProtectedArchive(RecordRoom room,
                                              RecordHistoryPart part,
                                              String filePath,
                                              String source,
                                              String action) {
        RecordHistory history = resolveHistory(part);
        return shouldSkipProtectedArchive(room, history, part, filePath, source, action);
    }

    public boolean shouldSkipProtectedArchive(RecordRoom room,
                                              RecordHistory history,
                                              RecordHistoryPart part,
                                              String filePath,
                                              String source,
                                              String action) {
        if (!isProtectedFromPartFileCleanup(history, part)) {
            return false;
        }
        log.info("[BLR] {}", LogKvs.event("PartFileCleanup.SkipProtectedArchive")
                .addIfNotBlank("source", source)
                .addIfNotBlank("action", action)
                .add("roomId", room == null ? null : room.getRoomId())
                .add("uname", room == null ? null : room.getUname())
                .add("historyId", history.getId())
                .addIfNotBlank("title", history.getTitle())
                .addIfNotBlank("bvid", history.getBvId())
                .addIfNotBlank("aid", history.getAvId())
                .add("code", history.getCode())
                .addIfNotBlank("protectedReason", protectedReason(history, part))
                .add("forceArchived", history.isForceArchived())
                .add("partId", part == null ? null : part.getId())
                .addIfNotBlank("partTitle", part == null ? null : part.getTitle())
                .addIfNotBlank("filePath", filePath)
                .add("deleteType", room == null ? null : room.getDeleteType()));
        return true;
    }

    public boolean isProtectedFromPartFileCleanup(RecordHistory history) {
        return history != null
                && (history.isForceArchived()
                || history.getCode() == -2
                || history.getCode() == -4
                || "TIMESTAMP_JUMP".equals(history.getPublishIssueType()));
    }

    public boolean isProtectedFromPartFileCleanup(RecordHistory history, RecordHistoryPart part) {
        if (history == null) {
            return false;
        }
        if (history.isForceArchived() || history.getCode() == -2 || history.getCode() == -4) {
            return true;
        }
        if (!"TIMESTAMP_JUMP".equals(history.getPublishIssueType())) {
            return false;
        }
        if (history.getPublishIssuePartCount() <= 0) {
            return true;
        }
        return part != null && "TIMESTAMP_JUMP".equals(part.getDeleteFailType());
    }

    public boolean shouldDeferPostUploadCleanup(RecordHistoryPart part) {
        RecordHistory history = resolveHistory(part);
        return history != null && history.isUpload() && !history.isPublish();
    }

    public boolean isPostUploadCleanupType(int deleteType) {
        return milestoneFor(deleteType) == CleanupMilestone.AFTER_UPLOAD;
    }

    public boolean isPostPublishCleanupType(int deleteType) {
        return milestoneFor(deleteType) == CleanupMilestone.AFTER_PUBLISH;
    }

    public boolean isPostAuditCleanupType(int deleteType) {
        return milestoneFor(deleteType) == CleanupMilestone.AFTER_AUDIT;
    }

    public boolean isPostRecordCloseCleanupType(int deleteType) {
        return milestoneFor(deleteType) == CleanupMilestone.AFTER_RECORD_CLOSE;
    }

    public boolean isScheduledCleanupType(int deleteType) {
        return milestoneFor(deleteType) == CleanupMilestone.SCHEDULED;
    }

    public CleanupMilestone milestoneFor(int deleteType) {
        return switch (deleteType) {
            case 1, 4 -> CleanupMilestone.AFTER_UPLOAD;
            case 2, 5, 11 -> CleanupMilestone.AFTER_AUDIT;
            case 6, 7 -> CleanupMilestone.AFTER_RECORD_CLOSE;
            case 3, 8 -> CleanupMilestone.SCHEDULED;
            case 9, 10 -> CleanupMilestone.AFTER_PUBLISH;
            default -> CleanupMilestone.NONE;
        };
    }

    private RecordHistory resolveHistory(RecordHistoryPart part) {
        if (part == null || part.getHistoryId() == null) {
            return null;
        }
        return historyRepository.findById(part.getHistoryId()).orElse(null);
    }

    private String protectedReason(RecordHistory history, RecordHistoryPart part) {
        if (history == null) {
            return null;
        }
        String codeReason = switch (history.getCode()) {
            case -2 -> "rejected";
            case -4 -> "locked";
            default -> null;
        };
        if (codeReason != null) {
            return codeReason;
        }
        if (history.isForceArchived()) {
            return "forceArchived";
        }
        if ("TIMESTAMP_JUMP".equals(history.getPublishIssueType())) {
            return history.getPublishIssuePartCount() <= 0 || (part != null && "TIMESTAMP_JUMP".equals(part.getDeleteFailType()))
                    ? "timestampJump"
                    : null;
        }
        return null;
    }
}
