package top.sshh.bililiverecoder.entity;

import java.time.LocalDateTime;

public record VideoCommentTaskStatusDto(Long taskId, int sequence, String state, String pinState,
                                        String content, String errorMessage, LocalDateTime updatedAt) {
    public static VideoCommentTaskStatusDto from(VideoCommentTask task) {
        return new VideoCommentTaskStatusDto(task.getId(), task.getSequence(), task.getState(),
                task.getPinState(), task.getContent(), task.getErrorMessage(), task.getUpdatedAt());
    }
}
