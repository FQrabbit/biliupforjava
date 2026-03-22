package top.sshh.bililiverecoder.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class PushNotifyClient {

    private static final String SERVER_CHAN3_SEND_URL = "https://%s.push.ft07.com/send/%s.send";
    private static final Pattern SERVER_CHAN3_UID_PATTERN = Pattern.compile("^sctp(\\d+)t.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BV_ID_PATTERN = Pattern.compile("BV[0-9A-Za-z]+");
    private static final List<String> KNOWN_PUSH_TAGS = Arrays.asList(
            "开始直播", "录制结束", "分P上传", "视频投稿", "高级弹幕", "视频评论", "云剪辑"
    );

    private PushNotifyClient() {
    }

    public static boolean canSend(RecordRoom room, String wxuid, String pushMsgTags, String tag) {
        if (!isTagEnabled(pushMsgTags, tag)) {
            return false;
        }
        return hasAnyChannel(room, wxuid);
    }

    public static String normalizePushMsgTags(String pushMsgTags) {
        Set<String> enabled = parseEnabledTags(pushMsgTags);
        if (enabled.isEmpty()) {
            return "";
        }
        return KNOWN_PUSH_TAGS.stream().filter(enabled::contains).collect(Collectors.joining(","));
    }

    private static boolean isTagEnabled(String pushMsgTags, String tag) {
        if (StringUtils.isBlank(pushMsgTags) || StringUtils.isBlank(tag)) {
            return false;
        }
        Set<String> enabled = parseEnabledTags(pushMsgTags);
        String target = normalizeTag(tag);
        return enabled.contains(target);
    }

    private static Set<String> parseEnabledTags(String rawTags) {
        if (StringUtils.isBlank(rawTags)) {
            return Collections.emptySet();
        }
        String normalizedRaw = normalizeSeparators(rawTags);
        String[] pieces = normalizedRaw.split(",");
        Set<String> enabled = new LinkedHashSet<>();
        for (String piece : pieces) {
            String token = normalizeTag(piece);
            if (StringUtils.isNotBlank(token) && KNOWN_PUSH_TAGS.contains(token)) {
                enabled.add(token);
            }
        }
        if (!enabled.isEmpty()) {
            return enabled;
        }

        // 兼容极旧数据：如直接拼接成一个长字符串（无分隔符），按已知标签做回退识别。
        String compact = normalizeTag(normalizedRaw);
        for (String knownTag : KNOWN_PUSH_TAGS) {
            if (compact.contains(normalizeTag(knownTag))) {
                enabled.add(knownTag);
            }
        }
        return enabled;
    }

    private static String normalizeSeparators(String raw) {
        return StringUtils.defaultString(raw)
                .replace("，", ",")
                .replace("、", ",")
                .replace("|", ",")
                .replace("；", ",")
                .replace(";", ",")
                .replace("\n", ",")
                .replace("\r", ",")
                .replace("\t", ",")
                .trim();
    }

    private static String normalizeTag(String raw) {
        String value = StringUtils.defaultString(raw).trim();
        while (value.startsWith("[") || value.startsWith("\"") || value.startsWith("'")) {
            value = value.substring(1).trim();
        }
        while (value.endsWith("]") || value.endsWith("\"") || value.endsWith("'")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value.replace(" ", "").replace("　", "");
    }

    public static boolean hasAnyChannel(RecordRoom room, String wxuid) {
        if (StringUtils.isNotBlank(wxuid)) {
            return true;
        }
        if (room == null) {
            return false;
        }
        return StringUtils.isNotBlank(room.getServerChanSendKey());
    }

    public static void sendParallel(RecordRoom room, Message message) {
        if (room == null || message == null) {
            return;
        }
        String wxuid = room.getWxuid();
        String serverChanSendKey = room.getServerChanSendKey();

        CompletableFuture<Void> wxFuture = CompletableFuture.runAsync(() -> sendWxPusher(message, room.getRoomId(), wxuid));
        CompletableFuture<Void> serverChanFuture = CompletableFuture.runAsync(() -> sendServerChan3(room, message, serverChanSendKey));
        CompletableFuture.allOf(wxFuture, serverChanFuture).join();
    }

    private static void sendWxPusher(Message message, String roomId, String wxuid) {
        if (StringUtils.isBlank(wxuid)) {
            return;
        }
        try {
            WxPusher.send(message);
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Notify.WxPusher.Send.Failed")
                    .add("roomId", roomId)
                    .addIfNotBlank("wxuid", wxuid)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private static void sendServerChan3(RecordRoom room, Message message, String serverChanSendKey) {
        if (StringUtils.isBlank(serverChanSendKey)) {
            return;
        }
        try {
            String rawContent = StringUtils.defaultString(message.getContent());
            String title = buildTitle(rawContent);
            String markdownContent = buildServerChanMarkdown(room, rawContent);
            String shortSummary = buildShortSummary(rawContent);

            Map<String, String> headers = new HashMap<>();
            Map<String, String> form = new HashMap<>();
            form.put("title", title);
            form.put("desp", markdownContent);
            if (StringUtils.isNotBlank(shortSummary)) {
                form.put("short", shortSummary);
            }
            if (StringUtils.isNotBlank(room.getServerChanChannel())) {
                form.put("tags", room.getServerChanChannel());
            }

            String apiUrl = buildServerChan3ApiUrl(serverChanSendKey);
            if (StringUtils.isBlank(apiUrl)) {
                log.warn("[BLR] {}", LogKvs.event("Notify.ServerChan3.Send.Failed")
                        .add("roomId", room.getRoomId())
                        .add("code", -1)
                        .add("message", "invalid serverChanSendKey format"));
                return;
            }
            String resp = HttpClientUtil.post(
                    apiUrl,
                    headers,
                    form,
                    false
            );
            JSONObject json = JSON.parseObject(resp);
            Integer code = json.getInteger("code");
            if (code == null || code != 0) {
                log.warn("[BLR] {}", LogKvs.event("Notify.ServerChan3.Send.Failed")
                        .add("roomId", room.getRoomId())
                        .add("code", code == null ? -1 : code)
                        .addIfNotBlank("message", json.getString("message"))
                        .addIfNotBlank("error", json.getString("error")));
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("Notify.ServerChan3.Send.Failed")
                    .add("roomId", room.getRoomId())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
    }

    private static String buildTitle(String text) {
        String content = StringUtils.defaultString(text);
        String title = content;
        int idx = content.indexOf('\n');
        if (idx > 0) {
            title = content.substring(0, idx);
        }
        title = title.trim();
        if (title.length() > 64) {
            title = title.substring(0, 64);
        }
        if (StringUtils.isBlank(title)) {
            return "biliupforjava通知";
        }
        return title;
    }

    private static String buildServerChanMarkdown(RecordRoom room, String text) {
        List<String> lines = splitLines(text);
        List<String> normalized = lines.stream()
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .toList();

        if (normalized.isEmpty()) {
            return "## biliupforjava 通知\n\n暂无详细内容";
        }

        String heading = normalized.get(0);
        LinkedHashMap<String, String> kv = new LinkedHashMap<>();
        List<String> details = new ArrayList<>();

        for (int i = 1; i < normalized.size(); i++) {
            String line = normalized.get(i);
            int sep = findKvSeparator(line);
            if (sep > 0) {
                String key = line.substring(0, sep).trim();
                String val = line.substring(sep + 1).trim();
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(val)) {
                    kv.put(key, val);
                    continue;
                }
            }
            details.add(line);
        }

        StringBuilder md = new StringBuilder();
        md.append("## ").append(escapeMarkdownHeadline(heading)).append("\n\n");

        if (!details.isEmpty()) {
            md.append("### 说明\n\n");
            for (String detail : details) {
                md.append("- ").append(escapeMarkdownInline(detail)).append("\n");
            }
            md.append("\n");
        }

        if (!kv.isEmpty()) {
            md.append("### 详情\n\n");
            for (Map.Entry<String, String> entry : kv.entrySet()) {
                md.append("- **")
                        .append(escapeMarkdownInline(entry.getKey()))
                        .append("**: ")
                        .append(formatValue(entry.getValue()))
                        .append("\n");
            }
            md.append("\n");
        }

        appendUsefulLinks(md, room, kv);
        appendMeta(md, room);
        return md.toString().trim();
    }

    private static void appendUsefulLinks(StringBuilder md, RecordRoom room, Map<String, String> kv) {
        String bv = extractBvId(kv);
        String roomId = room == null ? null : room.getRoomId();
        if (StringUtils.isBlank(bv) && StringUtils.isBlank(roomId)) {
            return;
        }
        md.append("### 链接\n\n");
        if (StringUtils.isNotBlank(bv)) {
            md.append("- 视频: https://www.bilibili.com/video/").append(bv).append("\n");
        }
        if (StringUtils.isNotBlank(roomId)) {
            md.append("- 直播间: https://live.bilibili.com/").append(roomId).append("\n");
        }
        md.append("\n");
    }

    private static void appendMeta(StringBuilder md, RecordRoom room) {
        md.append("---\n");
        md.append("来自 biliupforjava");
        if (room != null && StringUtils.isNotBlank(room.getUname())) {
            md.append(" | 主播: ").append(escapeMarkdownInline(room.getUname()));
        }
        if (room != null && StringUtils.isNotBlank(room.getRoomId())) {
            md.append(" | 房间ID: ").append(room.getRoomId());
        }
        md.append("\n");
    }

    private static String extractBvId(Map<String, String> kv) {
        for (Map.Entry<String, String> entry : kv.entrySet()) {
            String key = StringUtils.defaultString(entry.getKey());
            String value = StringUtils.defaultString(entry.getValue());
            if (key.toLowerCase().contains("bv")) {
                String bv = findBvInText(value);
                if (StringUtils.isNotBlank(bv)) {
                    return bv;
                }
            }
            String bv = findBvInText(value);
            if (StringUtils.isNotBlank(bv)) {
                return bv;
            }
        }
        return null;
    }

    private static String findBvInText(String text) {
        Matcher matcher = BV_ID_PATTERN.matcher(StringUtils.defaultString(text));
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    private static int findKvSeparator(String line) {
        int colonCn = line.indexOf('：');
        int colonEn = line.indexOf(':');
        if (colonCn < 0) {
            return colonEn;
        }
        if (colonEn < 0) {
            return colonCn;
        }
        return Math.min(colonCn, colonEn);
    }

    private static List<String> splitLines(String text) {
        String[] arr = StringUtils.defaultString(text).replace("\r\n", "\n").replace('\r', '\n').split("\\n");
        List<String> lines = new ArrayList<>(arr.length);
        for (String line : arr) {
            lines.add(line);
        }
        return lines;
    }

    private static String formatValue(String value) {
        String v = StringUtils.defaultString(value).trim();
        if (v.isEmpty()) {
            return "-";
        }
        if (v.contains("\n") || v.length() > 120) {
            return "```\n" + v + "\n```";
        }
        return escapeMarkdownInline(v);
    }

    private static String buildShortSummary(String text) {
        List<String> lines = splitLines(text).stream()
                .map(StringUtils::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
        if (lines.isEmpty()) {
            return "biliupforjava 通知";
        }
        StringBuilder sb = new StringBuilder(lines.get(0));
        for (int i = 1; i < lines.size() && sb.length() < 120; i++) {
            String line = lines.get(i);
            int sep = findKvSeparator(line);
            if (sep > 0) {
                String key = line.substring(0, sep).trim();
                String val = line.substring(sep + 1).trim();
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(val)) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(key).append(':').append(val);
                }
            }
        }
        String shortText = sb.toString().trim();
        if (shortText.length() > 120) {
            return shortText.substring(0, 120);
        }
        return shortText;
    }

    private static String escapeMarkdownHeadline(String text) {
        return StringUtils.defaultString(text).replace("\n", " ").trim();
    }

    private static String escapeMarkdownInline(String text) {
        return StringUtils.defaultString(text)
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_");
    }

    private static String buildServerChan3ApiUrl(String sendKey) {
        String trimmedSendKey = StringUtils.defaultString(sendKey).trim();
        Matcher matcher = SERVER_CHAN3_UID_PATTERN.matcher(trimmedSendKey);
        if (matcher.matches()) {
            return SERVER_CHAN3_SEND_URL.formatted(matcher.group(1), trimmedSendKey);
        }
        return null;
    }
}
