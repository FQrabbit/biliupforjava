package top.sshh.bililiverecoder.entity;

import java.time.LocalDateTime;

public record VideoVisibilityRestoreStatusDto(Long taskId, String state, int attemptCount,
                                              LocalDateTime nextAttemptAt, String errorMessage) {
    public static VideoVisibilityRestoreStatusDto from(VideoVisibilityRestoreTask task) {
        return new VideoVisibilityRestoreStatusDto(task.getId(), task.getState(), task.getAttemptCount(),
                task.getNextAttemptAt(), task.getErrorMessage());
    }
}
