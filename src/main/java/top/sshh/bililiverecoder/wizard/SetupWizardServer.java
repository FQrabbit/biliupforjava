package top.sshh.bililiverecoder.wizard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import top.sshh.bililiverecoder.util.ContainerUtils;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

public class SetupWizardServer {

    public static void checkAndRunWizardIfNeeded(String[] args) {
        // 允许用户通过开关强制控制是否启用向导（优先级最高）
        Boolean wizardEnabled = readWizardEnabledFlag();
        if (wizardEnabled != null) {
            if (!wizardEnabled) {
                return;
            }
        } else if (isRunningInContainer()) {
            // 容器环境默认跳过向导，避免 Docker 启动被误判
            return;
        }

        // 检查是否存在配置文件 (相对于进程所在目录)
        File currentDir = getProcessDir();
        File appYml = new File(currentDir, "application.yml");
        File appProps = new File(currentDir, "application.properties");
        File configAppYml = new File(currentDir, "config/application.yml");
        File configAppProps = new File(currentDir, "config/application.properties");

        if (appYml.exists() || appProps.exists() || configAppYml.exists() || configAppProps.exists()) {
            return;
        }

        // 检查启动参数是否带了配置或者工作路径
        for (String arg : args) {
            if (arg.contains("spring.config.location") || arg.contains("record.work-path")) {
                return;
            }
        }
        
        // 检查 JVM 环境变量参数
        if (System.getProperty("spring.config.location") != null || System.getProperty("record.work-path") != null) {
            return;
        }

        // 检查环境变量参数（Docker 场景常用）
        if (System.getenv("SPRING_CONFIG_LOCATION") != null
                || System.getenv("RECORD_WORK_PATH") != null
                || System.getenv("RECORD_WORK_PATH".toLowerCase()) != null) {
            return;
        }

        // 都没有，启动网页配置向导
        startWizardServer();
    }

    private static Boolean readWizardEnabledFlag() {
        String flag = firstNonBlank(
                System.getProperty("blr.setup.wizard"),
                System.getenv("BLR_SETUP_WIZARD"),
                System.getenv("blr_setup_wizard")
        );
        if (flag == null) {
            return null;
        }
        String normalized = flag.trim().toLowerCase();
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized)) {
            return Boolean.TRUE;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "off".equals(normalized)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isRunningInContainer() {
        return ContainerUtils.isRunningInContainer();
    }

    // 获取进程（Jar/Exe）所在的物理绝对路径
    private static File getProcessDir() {
        try {
            String path = SetupWizardServer.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();
            File jarFile = new File(path);
            if (jarFile.isFile()) {
                return jarFile.getParentFile();
            }
            return jarFile;
        } catch (Exception e) {
            return new File(System.getProperty("user.dir")); // 兜底方案
        }
    }

    private static void startWizardServer() {
        int port = 8080;
        HttpServer server = null;
        while (server == null && port < 8100) {
            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (IOException e) {
                port++;
            }
        }
        
        if (server == null) {
            System.err.println("无法启动配置向导服务，所有可用端口都被占用。请手动创建 application.yml 文件。");
            System.err.println("Failed to start the setup wizard server. All available ports are in use. Please create 'application.yml' manually.");
            System.exit(1);
        }

        server.createContext("/", new StaticResourceHandler());
        server.createContext("/api/setup", new SetupApiHandler(server));

        server.setExecutor(null); // 使用默认执行器
        server.start();

        String url = "http://localhost:" + port + "/html/setup.html";
        System.out.println("\n=======================================================");
        System.out.println("检测到当前环境没有任何配置文件，正在进入初始配置向导模式！");
        System.out.println("No configuration file detected. Entering initial setup wizard mode!");
        System.out.println("配置向导网页已启动，请在浏览器中打开以下地址完成简易配置：");
        System.out.println("The setup wizard has started. Please open the following URL in your browser to complete the configuration:");
        System.out.println("👉 " + url);
        System.out.println("=======================================================\n");

        openBrowser(url);

        // 阻塞主线程，保持向导服务运行
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private static void openBrowser(String url) {
        String os = System.getProperty("os.name").toLowerCase();
        Runtime rt = Runtime.getRuntime();
        try {
            if (os.contains("win")) {
                rt.exec("rundll32 url.dll,FileProtocolHandler " + url);
            } else if (os.contains("mac")) {
                rt.exec("open " + url);
            } else if (os.contains("nix") || os.contains("nux")) {
                String[] browsers = { "xdg-open", "google-chrome", "firefox", "opera", "epiphany", "konqueror", "conkeror", "midori", "kazehakase", "mozilla" };
                StringBuilder cmd = new StringBuilder();
                for (int i = 0; i < browsers.length; i++) {
                    if (i == 0) cmd.append(String.format("%s \"%s\"", browsers[i], url));
                    else cmd.append(String.format(" || %s \"%s\"", browsers[i], url));
                }
                rt.exec(new String[] { "sh", "-c", cmd.toString() });
            }
        } catch (Exception e) {
            System.out.println("无法自动打开浏览器，请手动复制地址访问。");
            System.out.println("Failed to open browser automatically. Please copy the URL and access it manually.");
        }
    }

    static class StaticResourceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/setup.html")) {
                path = "/html/setup.html";
            }
            
            InputStream is = SetupWizardServer.class.getResourceAsStream("/static" + path);
            if (is == null) {
                String response = "404 Not Found";
                t.sendResponseHeaders(404, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();
                return;
            }

            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html; charset=UTF-8";
            else if (path.endsWith(".css")) contentType = "text/css; charset=UTF-8";
            else if (path.endsWith(".js")) contentType = "application/javascript; charset=UTF-8";
            else if (path.endsWith(".svg")) contentType = "image/svg+xml";

            t.getResponseHeaders().set("Content-Type", contentType);
            t.sendResponseHeaders(200, 0);
            
            OutputStream os = t.getResponseBody();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = is.read(buffer)) != -1) {
                os.write(buffer, 0, count);
            }
            os.close();
            is.close();
        }
    }

    static class SetupApiHandler implements HttpHandler {
        private HttpServer server;

        public SetupApiHandler(HttpServer server) {
            this.server = server;
        }

        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equalsIgnoreCase(t.getRequestMethod())) {
                InputStreamReader isr = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                BufferedReader br = new BufferedReader(isr);
                String body = br.lines().collect(Collectors.joining());

                // 提取前端传来的 JSON 字段
                String port = extractJsonValue(body, "port");
                String workPath = normalizePath(extractJsonValue(body, "workPath"));
                String username = extractJsonValue(body, "username");
                String password = extractJsonValue(body, "password");
                String encoding = extractJsonValue(body, "encoding");
                String timezone = extractJsonValue(body, "timezone");
                String cachePath = normalizePath(extractJsonValue(body, "cachePath"));
                String jvmArgs = extractJsonValue(body, "jvmArgs");

                // 路径安全校验
                String pathError = validatePath(workPath);
                if (pathError != null) {
                    String response = "{\"success\":false,\"message\":\"工作路径无效: " + pathError.replace("\"", "\\\"") + "\"}";
                    t.getResponseHeaders().set("Content-Type", "application/json");
                    t.sendResponseHeaders(400, response.length());
                    OutputStream os = t.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                    return;
                }
                pathError = validatePath(cachePath);
                if (pathError != null) {
                    String response = "{\"success\":false,\"message\":\"缓存路径无效: " + pathError.replace("\"", "\\\"") + "\"}";
                    t.getResponseHeaders().set("Content-Type", "application/json");
                    t.sendResponseHeaders(400, response.length());
                    OutputStream os = t.getResponseBody();
                    os.write(response.getBytes());
                    os.close();
                    return;
                }

                // 生成 application.yml 内容
                StringBuilder yml = new StringBuilder();
                // 将 JVM 启动参数作为注释写入文件顶部，方便用户查阅
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
                
                // 将编码和时区配置也写入配置文件中，以便 Spring Boot 读取
                if (encoding != null && !encoding.isEmpty()) {
                    yml.append("spring:\n");
                    yml.append("  http:\n");
                    yml.append("    encoding:\n");
                    yml.append("      charset: \"").append(escapeYamlValue(encoding)).append("\"\n");
                    yml.append("      enabled: true\n");
                    yml.append("      force: true\n");
                }
                
                if (timezone != null && !timezone.isEmpty()) {
                    yml.append("  jackson:\n");
                    yml.append("    time-zone: \"").append(escapeYamlValue(timezone)).append("\"\n");
                }

                // 将 JVM 启动参数同时保存为可读属性，方便后续查询回填
                if (jvmArgs != null && !jvmArgs.isBlank()) {
                    yml.append("\napp:\n");
                    yml.append("  jvm-args: \"").append(escapeYamlValue(jvmArgs)).append("\"\n");
                }

                // 保存到进程所在目录的 application.yml，强制使用 UTF-8 编码
                File currentDir = getProcessDir();
                File targetYml = new File(currentDir, "application.yml");
                try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(targetYml), StandardCharsets.UTF_8)) {
                    writer.write(yml.toString());
                }

                String response = "{\"success\":true}";
                t.getResponseHeaders().set("Content-Type", "application/json");
                t.sendResponseHeaders(200, response.length());
                OutputStream os = t.getResponseBody();
                os.write(response.getBytes());
                os.close();

                System.out.println("配置已成功保存到 " + targetYml.getAbsolutePath() + "，向导进程即将退出。请手动重新启动程序！");
                System.out.println("Configuration saved successfully to '" + targetYml.getAbsolutePath() + "'. The wizard process is exiting. Please restart the program manually!");
                
                // 停止服务并退出
                new Thread(() -> {
                    try { Thread.sleep(1000); } catch (InterruptedException e) {}
                    server.stop(0);
                    System.exit(0);
                }).start();
            } else {
                t.sendResponseHeaders(405, -1);
            }
        }

        private String extractJsonValue(String json, String key) {
            // 匹配字符串
            String pattern = "\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
            if (m.find()) {
                return unescapeJsonString(m.group(1));
            }
            // 匹配数字
            pattern = "\"" + key + "\"\\s*:\\s*(\\d+)";
            m = java.util.regex.Pattern.compile(pattern).matcher(json);
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

        /**
         * 路径安全校验：检查路径遍历和 Windows 保留字符。
         * 返回错误消息表示不合法，返回 null 表示通过。
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
         * 注释文本净化：移除换行符，防止 YAML 注释行断裂导致注入。
         */
        private static String sanitizeForComment(String value) {
            if (value == null || value.isEmpty()) return value;
            return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        }

        /**
         * YAML 双引号字符串转义：确保写入 application.yml 的值不会破坏 YAML 语法。
         * 对反斜杠、双引号及控制字符进行转义。
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
    }
}
