package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum NotificationEventType {
    LIVE_STREAM_STARTED("live.stream.started", "开始直播"),
    RECORDING_ENDED("recording.ended", "录制结束"),
    PART_UPLOAD("upload.part", "分P上传"),
    VIDEO_PUBLISH("publish.video", "视频投稿"),
    HIGH_LEVEL_DANMAKU("danmaku.high-level", "高级弹幕"),
    VIDEO_COMMENT("comment.video", "视频评论"),
    HIGH_ENERGY_CUT("editor.high-energy-cut", "云剪辑");

    private final String key;
    private final String label;

    NotificationEventType(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static List<NotificationEventType> orderedValues() {
        return Arrays.asList(values());
    }

    public static Optional<NotificationEventType> fromKey(String key) {
        return Arrays.stream(values())
                .filter(type -> type.key.equals(key))
                .findFirst();
    }

    public static Optional<NotificationEventType> fromLegacyLabel(String label) {
        String normalized = normalize(label);
        return Arrays.stream(values())
                .filter(type -> normalize(type.label).equals(normalized))
                .findFirst();
    }

    private static String normalize(String raw) {
        return StringUtils.defaultString(raw).trim().replace(" ", "").replace("　", "");
    }
}
