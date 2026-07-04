package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSONObject;
import com.zjiecode.wxpusher.client.WxPusher;
import com.zjiecode.wxpusher.client.bean.Message;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.NotificationChannel;

@Component
public class WxPusherNotificationChannel implements NotificationChannelAdapter {

    public static final String TYPE = "wxpusher";

    @Value("${record.wx-push-token:}")
    private String wxToken;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public NotificationSendResult send(NotificationChannel channel, NotificationMessage notificationMessage) {
        JSONObject config = NotificationJson.parse(channel.getConfigJson());
        String uid = config.getString("uid");
        if (StringUtils.isBlank(uid)) {
            return NotificationSendResult.failed("WxPusher UID is empty");
        }
        if (StringUtils.isBlank(wxToken)) {
            return NotificationSendResult.failed("record.wx-push-token is empty");
        }
        try {
            Message message = new Message();
            message.setAppToken(wxToken);
            message.setContentType(Message.CONTENT_TYPE_TEXT);
            message.setContent(notificationMessage.getContent());
            message.setUid(uid);
            WxPusher.send(message);
            return NotificationSendResult.ok();
        } catch (Exception e) {
            return NotificationSendResult.failed(StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }
}
