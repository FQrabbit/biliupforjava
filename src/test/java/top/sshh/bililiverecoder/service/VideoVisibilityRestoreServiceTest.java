package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.VideoVisibilityRestoreTask;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.VideoVisibilityRestoreTaskRepository;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VideoVisibilityRestoreServiceTest {
    @Test
    void restoreIsNotDueUntilCommentWorkFinishes() {
        VideoVisibilityRestoreTaskRepository repository = mock(VideoVisibilityRestoreTaskRepository.class);
        BiliUserRepository users = mock(BiliUserRepository.class);
        VideoVisibilityRestoreTask stored = new VideoVisibilityRestoreTask();
        stored.setId(7L);
        stored.setHistoryId(3L);
        stored.setAccountId(11L);
        stored.setAid("12345");
        stored.setRestoreVisibility(1);
        stored.setState("COMPLETE");
        when(repository.findByHistoryId(3L)).thenReturn(Optional.of(stored));
        when(repository.findById(anyLong())).thenAnswer(invocation ->
                stored.getId().equals(invocation.getArgument(0)) ? Optional.of(stored) : Optional.empty());
        when(repository.save(any(VideoVisibilityRestoreTask.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VideoVisibilityRestoreService service = new VideoVisibilityRestoreService(repository, users);
        VideoVisibilityRestoreTask preparing = service.ensurePreparing(3L, 11L, "12345", 1);
        assertEquals("PREPARING", preparing.getState());
        assertTrue(service.markVideoPublic(preparing.getId()));
        assertEquals("ACTIVE", stored.getState());
        service.requestRestore(preparing.getId());
        assertEquals("PENDING", stored.getState());
        assertFalse(service.markVideoPublic(preparing.getId()));
    }
}
