package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum NotificationEventType {
    LIVE_STREAM_STARTED("live.stream.started", "开始直播", "直播", "room", "直播间开始直播时发送通知", true),
    LIVE_STREAM_ENDED("live.stream.ended", "直播下播", "直播", "room", "直播间从开播状态变为下播时发送通知", true),
    RECORDING_ENDED("recording.ended", "录制结束", "录制", "room", "已废弃，后续会替换为直播下播事件", false),
    PART_UPLOAD("upload.part", "分P上传", "上传", "room", "已废弃，后续会细分为上传失败等事件", false),
    VIDEO_PUBLISH("publish.video", "审核通过", "投稿", "room", "视频投稿后审核通过时发送通知", true, "视频投稿"),
    VIDEO_AUDIT_REJECTED("publish.audit.rejected", "审核退回", "投稿", "room", "视频投稿后审核被退回时发送通知", true),
    VIDEO_AUDIT_LOCKED("publish.audit.locked", "稿件锁定", "投稿", "room", "视频投稿后稿件被锁定时发送通知", true),
    WORKSPACE_USAGE_ALERT("workspace.usage.alert", "工作目录空间预警", "系统", "system", "工作目录所在磁盘空间达到阈值时发送通知", true),
    HIGH_LEVEL_DANMAKU("danmaku.high-level", "高级弹幕", "弹幕", "room", "已废弃", false),
    VIDEO_COMMENT("comment.video", "视频评论", "评论", "room", "已废弃", false),
    HIGH_ENERGY_CUT("editor.high-energy-cut", "云剪辑", "云剪辑", "room", "已废弃", false);

    private final String key;
    private final String label;
    private final String group;
    private final String scope;
    private final String description;
    private final boolean active;
    private final List<String> legacyLabels;

    NotificationEventType(String key, String label, String group, String scope, String description, boolean active) {
        this(key, label, group, scope, description, active, new String[0]);
    }

    NotificationEventType(String key, String label, String group, String scope, String description, boolean active, String... legacyLabels) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.scope = scope;
        this.description = description;
        this.active = active;
        this.legacyLabels = Arrays.asList(legacyLabels);
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String group() {
        return group;
    }

    public String scope() {
        return scope;
    }

    public boolean systemScope() {
        return "system".equals(scope);
    }

    public String description() {
        return description;
    }

    public boolean active() {
        return active;
    }

    public List<String> legacyLabels() {
        return legacyLabels;
    }

    public static List<NotificationEventType> orderedValues() {
        return Arrays.asList(values());
    }

    public static List<NotificationEventType> activeValues() {
        return Arrays.stream(values())
                .filter(NotificationEventType::active)
                .toList();
    }

    public static Optional<NotificationEventType> fromKey(String key) {
        return Arrays.stream(values())
                .filter(type -> type.key.equals(key))
                .findFirst();
    }

    public static Optional<NotificationEventType> fromLegacyLabel(String label) {
        String normalized = normalize(label);
        return Arrays.stream(values())
                .filter(type -> type.matchesLegacyLabel(normalized))
                .findFirst();
    }

    public boolean containsLegacyLabel(String compactText) {
        String normalizedText = normalize(compactText);
        if (normalizedText.contains(normalize(label))) {
            return true;
        }
        return legacyLabels.stream()
                .map(NotificationEventType::normalize)
                .anyMatch(normalizedText::contains);
    }

    private boolean matchesLegacyLabel(String normalizedLabel) {
        if (normalize(label).equals(normalizedLabel)) {
            return true;
        }
        return legacyLabels.stream()
                .map(NotificationEventType::normalize)
                .anyMatch(normalizedLabel::equals);
    }

    private static String normalize(String raw) {
        return StringUtils.defaultString(raw).trim().replace(" ", "").replace("　", "");
    }
}
