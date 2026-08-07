package top.sshh.bililiverecoder.job;

import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.service.SystemConfigService;
import top.sshh.bililiverecoder.util.HttpsTrustManager;
import top.sshh.bililiverecoder.util.LogKvs;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * 录播姬 Cookie 自动同步任务
 *
 * 定期把指定 B站账号的最新 Cookie 通过录播姬 WebApi 推送到录播姬实例，
 * 使录播姬在请求 B站接口时始终持有有效登录态，避免 Cookie 过期导致的录制异常
 *
 * 录播姬接口契约（来自 BililiveRecorder dev 分支源码）：
 *   POST {scheme}://{host}:{port}/api/config/global
 *   body: {"optionalCookie":{"hasValue":true,"value":"SESSDATA=xxx; bili_jct=yyy"}}
 * 录播姬开启 Basic Authentication 时需附带 Authorization: Basic base64(user:pass)
 *
 * 定时任务与前端“保存并立即同步”共用核心逻辑 {@link #performSync()}，
 * 后者会把结构化结果返回前端，用于展示成功/失败原因
 */
@Slf4j
@Component
public class BrecCookieSyncJob {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 录播姬全局配置接口路径，固定不变，避免用户手填长 URL 出错 */
    private static final String GLOBAL_CONFIG_PATH = "/api/config/global";

    private final OkHttpClient brecClient;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private BiliUserRepository userRepository;

    public BrecCookieSyncJob() {
        // 录播姬通常部署在内网，自签证书常见，这里复用项目的全信任 SSL 工厂；
        // 独立 client 不经过 B站 API 限速器，超时也设得更短
        HttpsTrustManager manager = new HttpsTrustManager();
        this.brecClient = new OkHttpClient().newBuilder()
                .sslSocketFactory(HttpsTrustManager.createSSLSocketFactory(), manager)
                .hostnameVerifier((hostname, session) -> true)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /** 同步结果的失败分类，供前端区分提示文案 */
    public enum SyncStatus {
        SUCCESS,
        CONFIG_INCOMPLETE,   // 配置不完整（缺地址/UID 等）
        NO_COOKIE,           // 目标账号不存在或无可用 Cookie
        UNREACHABLE,         // 无法访问录播姬（连接超时/拒绝/DNS 解析失败）
        AUTH_FAILED,         // Basic 认证失败（401/403）
        HTTP_ERROR,          // 录播姬返回其他非 2xx 状态
        UNKNOWN              // 其他未预期错误
    }

    /** 同步结果，承载是否成功、失败分类与给用户看的中文消息 */
    public static class SyncResult {
        public final boolean success;
        public final SyncStatus status;
        public final String message;
        public final Integer httpCode;

        private SyncResult(boolean success, SyncStatus status, String message, Integer httpCode) {
            this.success = success;
            this.status = status;
            this.message = message;
            this.httpCode = httpCode;
        }

        public static SyncResult ok(String message) {
            return new SyncResult(true, SyncStatus.SUCCESS, message, null);
        }

        public static SyncResult fail(SyncStatus status, String message) {
            return new SyncResult(false, status, message, null);
        }

        public static SyncResult fail(SyncStatus status, String message, Integer httpCode) {
            return new SyncResult(false, status, message, httpCode);
        }
    }

    // 每小时执行一次，启动后延迟 2 分钟首次执行，避开应用启动高峰
    @Scheduled(fixedDelay = 3600000, initialDelay = 120000)
    public void syncCookie() {
        if (!systemConfigService.isBrecCookieSyncEnabled()) {
            return;
        }
        performSync();
    }

    /**
     * 执行一次 Cookie 同步（读取配置、取账号 Cookie、推送到录播姬）
     * 不检查总开关，调用方自行决定是否在关闭时跳过；前端手动触发时无视开关也可测试连通性
     *
     * @return 结构化结果，包含成功/失败分类与中文消息
     */
    public SyncResult performSync() {
        String host = StringUtils.trimToEmpty(systemConfigService.getStringConfig(SystemConfigService.KEY_BREC_SYNC_HOST, ""));
        String port = StringUtils.trimToEmpty(systemConfigService.getStringConfig(SystemConfigService.KEY_BREC_SYNC_PORT, ""));
        String uidStr = StringUtils.trimToEmpty(systemConfigService.getStringConfig(SystemConfigService.KEY_BREC_SYNC_UID, ""));
        boolean https = systemConfigService.getBooleanConfig(SystemConfigService.KEY_BREC_SYNC_HTTPS, false);
        String username = systemConfigService.getStringConfig(SystemConfigService.KEY_BREC_SYNC_USERNAME, "");
        String password = systemConfigService.getStringConfig(SystemConfigService.KEY_BREC_SYNC_PASSWORD, "");

        if (StringUtils.isBlank(host) || StringUtils.isBlank(uidStr)) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.SkipIncompleteConfig")
                    .add("host", host)
                    .add("uid", uidStr));
            return SyncResult.fail(SyncStatus.CONFIG_INCOMPLETE, "配置不完整：请先填写录播姬地址并选择提供 Cookie 的账号");
        }

        long uid;
        try {
            uid = Long.parseLong(uidStr);
        } catch (NumberFormatException e) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.InvalidUid").add("uid", uidStr));
            return SyncResult.fail(SyncStatus.CONFIG_INCOMPLETE, "账号 UID 无效，请重新选择账号");
        }

        BiliBiliUser user = userRepository.findByUid(uid);
        if (user == null || StringUtils.isBlank(user.getCookies())) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.NoUsableCookie").add("uid", uid));
            return SyncResult.fail(SyncStatus.NO_COOKIE, "所选账号不存在或没有可用的 Cookie，请先登录该账号");
        }

        String cookie = toBrowserCookie(user.getCookies());
        if (StringUtils.isBlank(cookie)) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.EmptyCookieAfterConvert").add("uid", uid));
            return SyncResult.fail(SyncStatus.NO_COOKIE, "所选账号的 Cookie 解析后为空，请重新登录该账号");
        }

        String url = buildConfigUrl(https, host, port);
        SyncResult result = pushCookie(url, cookie, username, password);
        if (result.success) {
            log.info("[BLR] {}", LogKvs.event("BrecCookieSync.Success")
                    .add("uid", uid)
                    .add("uname", user.getUname())
                    .addUrl("url", url));
        }
        return result;
    }

    /**
     * 把数据库中保存的 Cookie 串转换为浏览器/录播姬所需的标准 HTTP Cookie 格式
     *
     * 数据库里的 Cookie 以 "name:value; " 形式保存（见 BiliBiliUserService.refreshToken），
     * 但浏览器与录播姬需要标准的 "name=value; " 形式。需要把每个键值对的第一个分隔符
     * 由冒号改为等号，同时兼容本身已是等号格式的历史数据，并去掉空片段
     *
     * @param rawCookie 数据库中的原始 Cookie 串，可能是 "SESSDATA:xxx; bili_jct:yyy; " 或已是等号格式
     * @return 形如 "SESSDATA=xxx; bili_jct=yyy" 的标准 Cookie 串
     */
    private String toBrowserCookie(String rawCookie) {
        if (StringUtils.isBlank(rawCookie)) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String segment : rawCookie.split(";")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 只识别每段的第一个分隔符：value 里可能含 = 或 :，不能用全局 replace
            int eq = trimmed.indexOf('=');
            int colon = trimmed.indexOf(':');
            int sep;
            if (eq >= 0 && (colon < 0 || eq < colon)) {
                // 已是等号格式（兼容历史数据），保持幂等直接采用
                sep = eq;
            } else if (colon >= 0) {
                sep = colon;
            } else {
                // 无分隔符的脏数据，跳过
                continue;
            }
            String name = trimmed.substring(0, sep).trim();
            String value = trimmed.substring(sep + 1).trim();
            if (name.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(name).append('=').append(value);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 拼接录播姬全局配置接口的完整 URL（协议 + host + 可选端口 + 固定路径） */
    private String buildConfigUrl(boolean https, String host, String port) {
        String scheme = https ? "https" : "http";
        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        if (StringUtils.isNotBlank(port)) {
            sb.append(":").append(port.trim());
        }
        sb.append(GLOBAL_CONFIG_PATH);
        return sb.toString();
    }

    /** 向录播姬推送 Cookie，返回带失败分类的结构化结果 */
    private SyncResult pushCookie(String url, String cookie, String username, String password) {
        JSONObject optionalCookie = new JSONObject();
        optionalCookie.put("hasValue", true);
        optionalCookie.put("value", cookie);
        JSONObject body = new JSONObject();
        body.put("optionalCookie", optionalCookie);

        Request.Builder builder = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toJSONString(), JSON_MEDIA_TYPE));
        if (StringUtils.isNotBlank(username)) {
            String token = Base64.getEncoder()
                    .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + token);
        }

        try (Response response = brecClient.newCall(builder.build()).execute()) {
            if (response.isSuccessful()) {
                return SyncResult.ok("Cookie 已成功推送到录播姬");
            }
            int code = response.code();
            if (code == 401 || code == 403) {
                log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.AuthFailed")
                        .add("code", code)
                        .addUrl("url", url));
                return SyncResult.fail(SyncStatus.AUTH_FAILED,
                        "认证失败（HTTP " + code + "）：录播姬开启了访问认证，请检查用户名和密码是否正确", code);
            }
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.HttpError")
                    .add("code", code)
                    .addUrl("url", url));
            return SyncResult.fail(SyncStatus.HTTP_ERROR,
                    "录播姬返回错误（HTTP " + code + "）：请确认地址、端口正确且目标确实是录播姬 WebApi", code);
        } catch (ConnectException e) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.Unreachable")
                    .addUrl("url", url).add("err", e.getMessage()).add("ex", "ConnectException"));
            return SyncResult.fail(SyncStatus.UNREACHABLE,
                    "无法访问录播姬：连接被拒绝，请确认录播姬已启动、地址和端口填写正确、且 WebApi 已开启");
        } catch (SocketTimeoutException e) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.Unreachable")
                    .addUrl("url", url).add("err", e.getMessage()).add("ex", "SocketTimeoutException"));
            return SyncResult.fail(SyncStatus.UNREACHABLE,
                    "无法访问录播姬：连接超时，请确认网络可达以及地址、端口是否正确");
        } catch (UnknownHostException e) {
            log.warn("[BLR] {}", LogKvs.event("BrecCookieSync.Unreachable")
                    .addUrl("url", url).add("err", e.getMessage()).add("ex", "UnknownHostException"));
            return SyncResult.fail(SyncStatus.UNREACHABLE,
                    "无法访问录播姬：地址无法解析，请检查录播姬地址（IP 或域名）是否填写正确");
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("BrecCookieSync.RequestFailed")
                    .addUrl("url", url)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
            return SyncResult.fail(SyncStatus.UNKNOWN,
                    "同步失败：" + (StringUtils.isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}
