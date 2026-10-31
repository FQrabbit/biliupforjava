package top.sshh.bililiverecoder.protection;

import com.alibaba.fastjson.JSON;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

public final class DiskProtectionHttp {
    public record Response(int status, String type, byte[] body, boolean challenge) {}
    private DiskProtectionHttp() {}

    public static Response handle(DiskSpaceGuard guard, String method, String path, String authorization,
                                  String csrfToken, String accept) throws IOException {
        if (path.equals("/recordWebHook") || path.equals("/webhook/blrec")) {
            return json(503, Map.of("diskProtection", true, "message", "磁盘空间保护中，录制事件未接收"));
        }
        if (!authorized(guard.settings(), authorization)) {
            return new Response(401, "text/plain;charset=UTF-8", "请使用原有账号密码登录磁盘保护页".getBytes(StandardCharsets.UTF_8), true);
        }
        if (method.equals("GET") && path.equals("/api/system/disk-protection")) return json(200, guard.status());
        if (method.equals("POST") && path.equals("/api/system/disk-protection/resume")) {
            if (!guard.csrfToken().equals(csrfToken)) return json(403, Map.of("message", "请刷新保护页后重试"));
            boolean accepted = guard.requestResume();
            return json(accepted ? 202 : 409, Map.of("accepted", accepted, "message",
                    accepted ? "正在重新检查并恢复，请稍后刷新" : "暂时不能恢复，请检查空间、稳定时间及当前收尾状态"));
        }
        if (method.equals("GET") && (path.equals("/disk-protection") || path.equals("/")
                || (accept != null && accept.contains("text/html")))) {
            try (var stream = DiskProtectionHttp.class.getResourceAsStream("/static/html/disk-protection.html")) {
                if (stream == null) throw new IOException("磁盘保护页面资源缺失");
                String html = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("__CSRF_TOKEN__", guard.csrfToken())
                        .replace("__CONTEXT_PATH__", escapeJs(guard.settings().contextPath()));
                return new Response(200, "text/html;charset=UTF-8", html.getBytes(StandardCharsets.UTF_8), false);
            }
        }
        return json(503, Map.of("diskProtection", true, "message", "磁盘空间保护中，请打开保护页查看状态"));
    }

    private static String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\u003c");
    }
    public static boolean authorized(DiskSpaceSettings settings, String authorization) {
        if (settings.userName().isBlank() || settings.password().isBlank()) return true;
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                (settings.userName() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8));
        return authorization != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8));
    }
    private static Response json(int status, Object body) {
        return new Response(status, "application/json;charset=UTF-8", JSON.toJSONBytes(body), false);
    }
}
