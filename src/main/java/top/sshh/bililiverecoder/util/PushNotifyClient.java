package top.sshh.bililiverecoder.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class PushNotifyClient {

    private static final String SERVER_CHAN3_SEND_URL = "https://%s.push.ft07.com/send/%s.send";
    private static final Pattern SERVER_CHAN3_UID_PATTERN = Pattern.compile("^sctp(\\d+)t.*$", Pattern.CASE_INSENSITIVE);

    private PushNotifyClient() {
    }

    public static boolean canSend(RecordRoom room, String wxuid, String pushMsgTags, String tag) {
        if (StringUtils.isBlank(pushMsgTags) || !pushMsgTags.contains(tag)) {
            return false;
        }
        return hasAnyChannel(room, wxuid);
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
            String title = buildTitle(message.getContent());
            String content = StringUtils.defaultString(message.getContent());

            Map<String, String> headers = new HashMap<>();
            Map<String, String> form = new HashMap<>();
            form.put("title", title);
            form.put("desp", content);
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

    private static String buildServerChan3ApiUrl(String sendKey) {
        String trimmedSendKey = StringUtils.defaultString(sendKey).trim();
        Matcher matcher = SERVER_CHAN3_UID_PATTERN.matcher(trimmedSendKey);
        if (matcher.matches()) {
            return SERVER_CHAN3_SEND_URL.formatted(matcher.group(1), trimmedSendKey);
        }
        return null;
    }
}
