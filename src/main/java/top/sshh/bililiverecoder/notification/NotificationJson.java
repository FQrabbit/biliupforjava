package top.sshh.bililiverecoder.notification;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

final class NotificationJson {

    private NotificationJson() {
    }

    static JSONObject parse(String json) {
        if (StringUtils.isBlank(json)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    static String object(String key, String value) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key, value);
        return JSON.toJSONString(map);
    }

    static String object(String key1, String value1, String key2, String value2) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(key1, value1);
        map.put(key2, value2);
        return JSON.toJSONString(map);
    }
}
