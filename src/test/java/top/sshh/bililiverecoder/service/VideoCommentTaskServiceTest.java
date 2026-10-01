package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.VideoCommentTask;
import top.sshh.bililiverecoder.entity.data.BiliReply;
import top.sshh.bililiverecoder.repo.VideoCommentTaskRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoCommentTaskServiceTest {
    @Test
    void updatesUntouchedCommentRowsInPlaceWhenTheRequestChanges() {
        VideoCommentTaskRepository repository = mock(VideoCommentTaskRepository.class);
        VideoCommentTask first = task(11L, 0);
        VideoCommentTask second = task(12L, 1);
        List<VideoCommentTask> existing = new ArrayList<>(List.of(first, second));
        when(repository.findByHistoryIdOrderBySequenceAsc(5L)).thenReturn(existing);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<VideoCommentTask> updated = new VideoCommentTaskService(repository)
                .prepare(5L, 20L, List.of(reply("aid-1", "updated root"), reply("aid-2", "updated reply")));

        assertSame(first, updated.get(0));
        assertSame(second, updated.get(1));
        assertEquals("updated root", first.getContent());
        assertEquals("aid-2", second.getAid());
        assertEquals("READY", first.getState());
        verify(repository, never()).deleteAll(org.mockito.ArgumentMatchers.<VideoCommentTask>anyList());
    }

    @Test
    void unresolvedCommentSubmissionBlocksAllAutomaticResume() {
        VideoCommentTaskService service = new VideoCommentTaskService(mock(VideoCommentTaskRepository.class));
        VideoCommentTask ready = task(1L, 0);
        ready.setState("READY");
        VideoCommentTask unknown = task(2L, 1);
        unknown.setState("NEEDS_ACTION");
        VideoCommentTask interrupted = task(3L, 2);
        interrupted.setState("SUBMITTING");

        assertFalse(service.blocksAutomaticResume(List.of(ready)));
        assertTrue(service.blocksAutomaticResume(List.of(ready, unknown)));
        assertTrue(service.blocksAutomaticResume(List.of(ready, interrupted)));
        VideoCommentTask pinInterrupted = task(4L, 3);
        pinInterrupted.setState("SENT");
        pinInterrupted.setPinState("SUBMITTING");
        assertTrue(service.blocksAutomaticResume(List.of(ready, pinInterrupted)));
    }

    @Test
    void forceArchiveCancelsReadyCommentsAndPendingPinsButBlocksInflightPin() {
        VideoCommentTaskRepository repository = mock(VideoCommentTaskRepository.class);
        VideoCommentTask ready = task(1L, 0);
        VideoCommentTask sent = task(2L, 1);
        sent.setState("SENT");
        sent.setPinState("RETRY");
        VideoCommentTask pinInFlight = task(3L, 2);
        pinInFlight.setState("SENT");
        pinInFlight.setPinState("SUBMITTING");
        List<VideoCommentTask> tasks = new ArrayList<>(List.of(ready, sent, pinInFlight));
        when(repository.findByHistoryIdOrderBySequenceAsc(5L)).thenReturn(tasks);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        VideoCommentTaskService service = new VideoCommentTaskService(repository);

        assertTrue(service.forceArchiveBlockReason(5L).contains("置顶任务仍在提交"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.cancelUnsubmittedForHistory(5L));
        assertEquals("READY", ready.getState());
        pinInFlight.setPinState("PENDING");
        int changed = service.cancelUnsubmittedForHistory(5L);

        assertEquals(3, changed);
        assertEquals("CANCELLED", ready.getState());
        assertEquals("CANCELLED", ready.getPinState());
        assertEquals("SENT", sent.getState());
        assertEquals("CANCELLED", sent.getPinState());
        assertEquals("CANCELLED", pinInFlight.getPinState());
    }

    private static VideoCommentTask task(Long id, int sequence) {
        VideoCommentTask task = new VideoCommentTask();
        task.setId(id);
        task.setHistoryId(5L);
        task.setAccountId(10L);
        task.setSequence(sequence);
        task.setRequestHash("old-" + sequence);
        task.setAid("old-aid-" + sequence);
        task.setContent("old content");
        task.setState("READY");
        task.setPinState(sequence == 0 ? "PENDING" : "NONE");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    private static BiliReply reply(String aid, String content) {
        BiliReply reply = new BiliReply();
        reply.setOid(aid);
        reply.setMessage(content);
        return reply;
    }
}
