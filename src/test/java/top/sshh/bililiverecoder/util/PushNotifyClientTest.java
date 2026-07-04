package top.sshh.bililiverecoder.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PushNotifyClientTest {

    @Test
    void normalizePushMsgTagsShouldPreserveKnownOrder() {
        assertEquals("开始直播,分P上传,视频投稿",
                PushNotifyClient.normalizePushMsgTags("视频投稿，开始直播|分P上传"));
    }

    @Test
    void normalizePushMsgTagsShouldFallbackForCompactLegacyText() {
        assertEquals("开始直播,录制结束",
                PushNotifyClient.normalizePushMsgTags("开始直播录制结束"));
    }

    @Test
    void isTagEnabledShouldAcceptDifferentSeparators() {
        assertTrue(PushNotifyClient.isTagEnabled("开始直播；视频投稿", " 视频 投稿 "));
        assertFalse(PushNotifyClient.isTagEnabled("开始直播", "高级弹幕"));
    }
}
