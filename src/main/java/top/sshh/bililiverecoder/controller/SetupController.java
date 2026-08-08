package top.sshh.bililiverecoder.controller;

import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.util.ContainerUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
public class SetupController {

    private final Environment env;

    public SetupController(Environment env) {
        this.env = env;
    }

    @GetMapping("/api/setup/config")
    public Map<String, Object> getCurrentConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("port", env.getProperty("server.port", "44122"));
        config.put("workPath", env.getProperty("record.work-path", ""));
        config.put("username", env.getProperty("record.userName", ""));
        // 密码仅返回是否已设置，不泄露明文
        String password = env.getProperty("record.password", "");
        config.put("password", password.isEmpty() ? "" : "******");
        config.put("encoding", env.getProperty("spring.http.encoding.charset", "UTF-8"));
        config.put("timezone", env.getProperty("spring.jackson.time-zone", "Asia/Shanghai"));
        config.put("cachePath", env.getProperty("record.preview.cache-path", ""));
        config.put("jvmArgs", env.getProperty("app.jvm-args", ""));
        config.put("containerized", ContainerUtils.isRunningInContainer());
        String jdbcUrl = env.getProperty("spring.datasource.hikari.jdbc-url",
                env.getProperty("spring.datasource.url", ""));
        boolean embeddedH2 = jdbcUrl != null && jdbcUrl.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:h2:");
        config.put("h2DatabaseFollowsWorkPath", embeddedH2);
        config.put("databasePath", embeddedH2
                ? normalizePath(env.getProperty("record.work-path", "")) + "/db" : "");
        config.put("workPathChangeWarning", embeddedH2
                ? "本地 H2 数据库位于 work-path/db；本次不会自动迁移数据库，重启后请在目录变更弹窗中选择处理方式。"
                : "当前使用外部数据库；重启后请在目录变更弹窗中选择历史素材路径处理方式。");
        return config;
    }

    @PostMapping("/api/setup")
    public Map<String, Object> saveConfig(@RequestBody String body) {
        String port = extractJsonValue(body, "port");
        String workPath = normalizePath(extractJsonValue(body, "workPath"));
        String username = extractJsonValue(body, "username");
        String password = extractJsonValue(body, "password");
        String encoding = extractJsonValue(body, "encoding");
        String timezone = extractJsonValue(body, "timezone");
        String cachePath = normalizePath(extractJsonValue(body, "cachePath"));
        String jvmArgs = extractJsonValue(body, "jvmArgs");
        boolean confirmH2WorkPathRisk = Boolean.parseBoolean(extractJsonValue(body, "confirmH2WorkPathRisk"));

        String oldWorkPath = normalizePath(env.getProperty("record.work-path", ""));
        boolean workPathChanged = workPath != null && !workPath.isBlank()
                && oldWorkPath != null && !oldWorkPath.isBlank()
                && !sameConfiguredPath(workPath, oldWorkPath);
        String jdbcUrl = env.getProperty("spring.datasource.hikari.jdbc-url",
                env.getProperty("spring.datasource.url", ""));
        boolean embeddedH2 = jdbcUrl != null
                && jdbcUrl.toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:h2:");
        if (workPathChanged && embeddedH2 && !confirmH2WorkPathRisk) {
            return errorResult("修改工作目录不会自动迁移 work-path/db 中的H2数据库，请确认已迁移数据库或正在使用MySQL");
        }

        // 路径安全校验
        String pathError = validatePath(workPath);
        if (pathError != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "工作路径无效: " + pathError);
            return result;
        }
        pathError = validatePath(cachePath);
        if (pathError != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", "缓存路径无效: " + pathError);
            return result;
        }

        // port 范围校验
        if (port != null && !port.isEmpty()) {
            try {
                int portNum = Integer.parseInt(port);
                if (portNum < 1 || portNum > 65535) {
                    return errorResult("端口号必须在 1-65535 范围内，当前值: " + portNum);
                }
            } catch (NumberFormatException e) {
                return errorResult("端口号格式无效: " + port);
            }
        }

        // workPath 存在且可写校验
        if (workPath != null && !workPath.isEmpty()) {
            String pathErr = validatePathAccess(workPath);
            if (pathErr != null) {
                return errorResult("工作路径不可用: " + pathErr);
            }
        }

        // cachePath 存在且可写校验
        if (cachePath != null && !cachePath.isEmpty()) {
            String pathErr = validatePathAccess(cachePath);
            if (pathErr != null) {
                return errorResult("缓存路径不可用: " + pathErr);
            }
        }

        // timezone 校验
        if (timezone != null && !timezone.isEmpty()) {
            if (!ZoneId.getAvailableZoneIds().contains(timezone)) {
                return errorResult("不被支持的时区: " + timezone);
            }
        }

        // encoding 校验
        if (encoding != null && !encoding.isEmpty()) {
            if (!Charset.isSupported(encoding)) {
                return errorResult("不被支持的编码: " + encoding);
            }
        }

        // 密码为 ****** 表示未修改，沿用旧密码
        if ("******".equals(password)) {
            password = env.getProperty("record.password", "");
        }

        // 生成 application.yml 内容
        StringBuilder yml = new StringBuilder();
        if (jvmArgs != null && !jvmArgs.isBlank()) {
            String safeJvm = sanitizeForComment(jvmArgs);
            yml.append("# JVM 启动参数 (需在启动脚本中手动传入): ").append(safeJvm).append("\n");
            yml.append("# 例如启动命令: java ").append(safeJvm).append(" -jar biliupforjava.jar\n\n");
        }
        yml.append("server:\n");
        yml.append("  port: ").append(port == null || port.isEmpty() ? "44122" : escapeYamlValue(port)).append("\n\n");

        yml.append("record:\n");
        if (workPath != null && !workPath.isEmpty()) {
            yml.append("  work-path: \"").append(escapeYamlValue(workPath)).append("\"\n");
        }
        if (username != null && !username.isEmpty()) {
            yml.append("  userName: \"").append(escapeYamlValue(username)).append("\"\n");
        }
        if (password != null && !password.isEmpty()) {
            yml.append("  password: \"").append(escapeYamlValue(password)).append("\"\n");
        }
        if (cachePath != null && !cachePath.isBlank()) {
            yml.append("  preview:\n");
            yml.append("    cache-path: \"").append(escapeYamlValue(cachePath)).append("\"\n");
        }

        if (encoding != null && !encoding.isEmpty()) {
            yml.append("spring:\n");
            yml.append("  http:\n");
            yml.append("    encoding:\n");
            yml.append("      charset: \"").append(escapeYamlValue(encoding)).append("\"\n");
            yml.append("      enabled: true\n");
            yml.append("      force: true\n");
        }
        if (timezone != null && !timezone.isEmpty()) {
            if (encoding == null || encoding.isEmpty()) {
                yml.append("spring:\n");
            }
            yml.append("  jackson:\n");
            yml.append("    time-zone: \"").append(escapeYamlValue(timezone)).append("\"\n");
        }
        if (jvmArgs != null && !jvmArgs.isBlank()) {
            yml.append("\napp:\n");
            yml.append("  jvm-args: \"").append(escapeYamlValue(jvmArgs)).append("\"\n");
        }

        // 保存到进程所在目录的 application.yml
        File currentDir = getProcessDir();
        File targetYml = new File(currentDir, "application.yml");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(targetYml), StandardCharsets.UTF_8)) {
            writer.write(yml.toString());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage());
            return result;
        }
    }

    private static boolean sameConfiguredPath(String left, String right) {
        String a = java.nio.file.Paths.get(left).toAbsolutePath().normalize().toString();
        String b = java.nio.file.Paths.get(right).toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private static File getProcessDir() {
        try {
            String path = SetupController.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(path);
            if (jarFile.isFile()) {
                return jarFile.getParentFile();
            }
            return jarFile;
        } catch (URISyntaxException e) {
            return new File(System.getProperty("user.dir"));
        }
    }

    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
        Matcher m = Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return unescapeJsonString(m.group(1));
        }
        pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
        m = Pattern.compile(pattern).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        pattern = "\"" + key + "\"\\s*:\\s*(true|false)";
        m = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /**
     * JSON 字符串反转义：将 JSON 文本中的转义序列还原为实际字符
     * 例如 {@code D:\\录播姬} → {@code D:\录播姬}
     */
    private static String unescapeJsonString(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '\\': sb.append('\\'); break;
                    case '"':  sb.append('"'); break;
                    case '/':  sb.append('/'); break;
                    case 'b':  sb.append('\b'); break;
                    case 'f':  sb.append('\f'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                String hex = s.substring(i + 1, i + 5);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('\\').append('u');
                            }
                        } else {
                            sb.append('\\').append('u');
                        }
                        break;
                    default:
                        sb.append('\\').append(next);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 路径规范化：将反斜杠统一转为正斜杠
     * Java File 类在 Windows/Linux 上均能正确处理正斜杠
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        return path.replace('\\', '/');
    }

    /**
     * 路径安全校验：检查路径遍历和 Windows 保留字符
     * 返回错误消息表示不合法，返回 null 表示通过
     */
    private static String validatePath(String path) {
        if (path == null || path.isEmpty()) return null;
        // 检查 Windows 保留字符
        for (char c : path.toCharArray()) {
            if (c == '<' || c == '>' || c == '"' || c == '|' || c == '?' || c == '*') {
                return "路径包含非法字符: " + c;
            }
        }
        // 检查路径遍历 ../
        String normalized = path.replace('\\', '/');
        for (String seg : normalized.split("/")) {
            if ("..".equals(seg)) {
                return "路径不允许包含 '..' 上级目录引用";
            }
        }
        return null;
    }

    /**
     * 注释文本净化：移除换行符，防止 YAML 注释行断裂导致注入
     */
    private static String sanitizeForComment(String value) {
        if (value == null || value.isEmpty()) return value;
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * YAML 双引号字符串转义：确保写入 application.yml 的值不会破坏 YAML 语法
     * 对反斜杠、双引号及控制字符进行转义
     */
    private static String escapeYamlValue(String value) {
        if (value == null || value.isEmpty()) return value;
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("message", message);
        return result;
    }

    /**
     * 检查路径是否为可写目录，或者它的上级目录存在且可写，方便首次使用时创建
     */
    private static String validatePathAccess(String path) {
        File dir = new File(path);
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                return "该路径已存在但不是目录";
            }
            if (!dir.canWrite()) {
                return "该目录不可写，请检查权限";
            }
            return null;
        }
        // 目录不存在时，检查上级目录能不能创建它
        File parent = dir.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.exists()) {
            return "上级目录不存在，无法创建";
        }
        if (!parent.canWrite()) {
            return "上级目录不可写，无法创建";
        }
        return null;
    }
}
