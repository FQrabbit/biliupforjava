package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.util.HttpClientUtil;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ServerChan3NotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "serverchan3";

    private static final String SERVER_CHAN3_SEND_URL = "https://%s.push.ft07.com/send/%s.send";
    private static final Pattern SERVER_CHAN3_UID_PATTERN = Pattern.compile("^sctp(\\d+)t.*$", Pattern.CASE_INSENSITIVE);
    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel, NotificationMessage notificationMessage) {
        JSONObject config = NotificationJson.parse(channel.getConfigJson());
        JSONObject secret = NotificationJson.parse(channel.getSecretJson());
        String sendKey = secret.getString("sendKey");
        if (StringUtils.isBlank(sendKey)) {
            return NotificationSendResult.failed("ServerChan3 SendKey is empty");
        }
        String apiUrl = buildServerChan3ApiUrl(sendKey);
        if (StringUtils.isBlank(apiUrl)) {
            return NotificationSendResult.failed("invalid ServerChan3 SendKey format");
        }
        try {
            String rawContent = StringUtils.defaultString(notificationMessage.getContent());
            String title = buildTitle(rawContent);
            String markdownContent = buildServerChanMarkdown(notificationMessage, rawContent);
            String shortSummary = buildShortSummary(rawContent);

            Map<String, String> headers = new HashMap<>();
            Map<String, String> form = new HashMap<>();
            form.put("title", title);
            form.put("desp", markdownContent);
            if (StringUtils.isNotBlank(shortSummary)) {
                form.put("short", shortSummary);
            }
            String tags = config.getString("tags");
            if (StringUtils.isNotBlank(tags)) {
                form.put("tags", tags);
            }

            String resp = HttpClientUtil.post(
                    apiUrl,
                    headers,
                    form,
                    false,
                    LogKvs.event("Http.Notify.ServerChan3.Request.Failed")
                            .addIfNotBlank("roomId", notificationMessage.getRoomId())
                            .addIfNotBlank("notifyTitle", title)
                            .addIfNotBlank("serverChanUid", extractServerChan3Uid(sendKey)),
                    false
            );
            if (StringUtils.isBlank(resp)) {
                return NotificationSendResult.failed("empty ServerChan3 response");
            }
            JSONObject json = JSON.parseObject(resp);
            Integer code = json.getInteger("code");
            if (code == null || code != 0) {
                String message = StringUtils.defaultIfBlank(json.getString("message"), json.getString("error"));
                return NotificationSendResult.failed("ServerChan3 code=" + (code == null ? -1 : code) + ", " + StringUtils.defaultString(message));
            }
            return NotificationSendResult.ok();
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    private static String extractServerChan3Uid(String sendKey) {
        String trimmedSendKey = StringUtils.defaultString(sendKey).trim();
        Matcher matcher = SERVER_CHAN3_UID_PATTERN.matcher(trimmedSendKey);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
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

    private static String buildServerChanMarkdown(NotificationMessage message, String text) {
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

        return md.toString().trim();
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
