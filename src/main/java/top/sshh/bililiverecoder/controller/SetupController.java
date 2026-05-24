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
import java.nio.charset.StandardCharsets;
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

        // 密码为 ****** 表示未修改，沿用旧密码
        if ("******".equals(password)) {
            password = env.getProperty("record.password", "");
        }

        // 生成 application.yml 内容
        StringBuilder yml = new StringBuilder();
        if (jvmArgs != null && !jvmArgs.isBlank()) {
            yml.append("# JVM 启动参数 (需在启动脚本中手动传入): ").append(jvmArgs).append("\n");
            yml.append("# 例如启动命令: java ").append(jvmArgs).append(" -jar biliupforjava.jar\n\n");
        }
        yml.append("server:\n");
        yml.append("  port: ").append(port == null || port.isEmpty() ? "44122" : port).append("\n\n");

        yml.append("record:\n");
        if (workPath != null && !workPath.isEmpty()) {
            yml.append("  work-path: \"").append(workPath).append("\"\n");
        }
        if (username != null && !username.isEmpty()) {
            yml.append("  userName: \"").append(username).append("\"\n");
        }
        if (password != null && !password.isEmpty()) {
            yml.append("  password: \"").append(password).append("\"\n");
        }
        if (cachePath != null && !cachePath.isBlank()) {
            yml.append("  preview:\n");
            yml.append("    cache-path: \"").append(cachePath).append("\"\n");
        }

        if (encoding != null && !encoding.isEmpty()) {
            yml.append("spring:\n");
            yml.append("  http:\n");
            yml.append("    encoding:\n");
            yml.append("      charset: ").append(encoding).append("\n");
            yml.append("      enabled: true\n");
            yml.append("      force: true\n");
        }
        if (timezone != null && !timezone.isEmpty()) {
            if (encoding == null || encoding.isEmpty()) {
                yml.append("spring:\n");
            }
            yml.append("  jackson:\n");
            yml.append("    time-zone: \"").append(timezone).append("\"\n");
        }
        if (jvmArgs != null && !jvmArgs.isBlank()) {
            yml.append("\napp:\n");
            yml.append("  jvm-args: \"").append(jvmArgs).append("\"\n");
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
        return "";
    }

    /**
     * JSON 字符串反转义：将 JSON 文本中的转义序列还原为实际字符。
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
     * 路径规范化：将反斜杠统一转为正斜杠。
     * Java File 类在 Windows/Linux 上均能正确处理正斜杠。
     */
    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) return path;
        return path.replace('\\', '/');
    }
}
