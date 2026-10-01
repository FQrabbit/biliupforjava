package top.sshh.bililiverecoder.entity;

import java.util.List;

public record HistoryPostPublishStatusDto(Long historyId,
                                          List<VideoCommentTaskStatusDto> comments,
                                          VideoVisibilityRestoreStatusDto visibilityRestore) {}
