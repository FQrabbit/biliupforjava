package top.sshh.bililiverecoder.job;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// 这里主要守住评论发送时的分P页码兜底顺序
// 退回稿件、重新编辑分P之后，本地 page 可能会变旧，评论定位错了后面会很难排查
class LiveMsgSendSyncTest {

    @Test
    void resolveReplyPagePrefersSyncedPage() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(3);
        part.setPartOrder(2);

        assertEquals(3, LiveMsgSendSync.resolveReplyPage(part, List.of(part)));
    }

    @Test
    void resolveReplyPageFallsBackToOrderedPartIndexBeforePartOrder() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(0);
        part.setPartOrder(2);

        assertEquals(1, LiveMsgSendSync.resolveReplyPage(part, List.of(part)));
    }

    @Test
    void resolveReplyPageFallsBackToPartOrderWithoutOrderedParts() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(0);
        part.setPartOrder(4);

        assertEquals(4, LiveMsgSendSync.resolveReplyPage(part, null));
    }

    @Test
    void resolveReplyPageFallsBackToOrderedPartIndex() {
        RecordHistoryPart first = new RecordHistoryPart();
        first.setPage(0);

        RecordHistoryPart second = new RecordHistoryPart();
        second.setPage(0);

        assertEquals(2, LiveMsgSendSync.resolveReplyPage(second, List.of(first, second)));
    }

    @Test
    void resolveReplyPageNeverReturnsZero() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(0);
        part.setPartOrder(0);

        assertEquals(1, LiveMsgSendSync.resolveReplyPage(part, null));
    }

    @Test
    void replyPageResolverUsesOnlineCidBeforeStaleLocalPage() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(2);
        part.setCid(100L);

        LiveMsgSendSync.ReplyPageResolver resolver = LiveMsgSendSync.ReplyPageResolver.fromOnlineSnapshot(
                List.of(part),
                partInfo(video(1, 100L, "online-1", "fn-1", "P1")));

        assertEquals(1, resolver.resolve(part));
    }

    @Test
    void replyPageResolverUsesOnlineFileNameWhenCidMissing() {
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(0);
        part.setPartOrder(2);
        part.setFileName("abc.m4s");

        LiveMsgSendSync.ReplyPageResolver resolver = LiveMsgSendSync.ReplyPageResolver.fromOnlineSnapshot(
                List.of(part),
                partInfo(video(1, 0L, "online-1", "abc.m4s", "P1")));

        assertEquals(1, resolver.resolve(part));
    }

    @Test
    void replyPageResolverIgnoresDuplicateOnlineTitleKeys() {
        // 标题重复时不要硬继续，宁愿回到本地顺序
        RecordHistoryPart part = new RecordHistoryPart();
        part.setPage(0);
        part.setPartOrder(3);
        part.setTitle("same-title");

        LiveMsgSendSync.ReplyPageResolver resolver = LiveMsgSendSync.ReplyPageResolver.fromOnlineSnapshot(
                null,
                partInfo(
                        video(1, 101L, "same-title", "fn-1", "same-title"),
                        video(2, 102L, "same-title", "fn-2", "same-title")));

        assertEquals(3, resolver.resolve(part));
    }

    private static BiliVideoPartInfoResponse partInfo(BiliVideoPartInfoResponse.Video... videos) {
        BiliVideoPartInfoResponse response = new BiliVideoPartInfoResponse();
        response.setCode(0);
        BiliVideoPartInfoResponse.BiliVideoInfo data = new BiliVideoPartInfoResponse.BiliVideoInfo();
        data.setVideos(List.of(videos));
        response.setData(data);
        return response;
    }

    private static BiliVideoPartInfoResponse.Video video(int page, long cid, String title, String filename, String partTitle) {
        BiliVideoPartInfoResponse.Video video = new BiliVideoPartInfoResponse.Video();
        video.setPage(page);
        video.setCid(cid);
        video.setTitle(title);
        video.setFilename(filename);
        video.setPart(partTitle);
        return video;
    }
}
