package top.sshh.bililiverecoder.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FrontendVersionService {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "window\\.BILIUPFORJAVA_VERSION\\s*=\\s*['\"]([^'\"]+)['\"]"
    );
    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
            "(<script[^>]*src=[\"'])([^\"']+)([\"'][^>]*>)"
    );
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(<link[^>]*href=[\"'])([^\"']+)([\"'][^>]*>)"
    );
    private static final Pattern HEAD_PATTERN = Pattern.compile("(?i)<head>");

    private final long startupEpochMs = System.currentTimeMillis();
    private final String version;
    private final String buildId;

    public FrontendVersionService() {
        this.version = readVersionFromVersionJs();
        this.buildId = buildBuildId(version, startupEpochMs);
    }

    public String getVersion() {
        return version;
    }

    public String getBuildId() {
        return buildId;
    }

    public long getStartupEpochMs() {
        return startupEpochMs;
    }

    public String getStartupAt() {
        return Instant.ofEpochMilli(startupEpochMs).toString();
    }

    public String readStaticText(String resourcePath) throws IOException {
        ClassPathResource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream is = resource.getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        }
    }

    public String renderHtml(String html) {
        return renderHtml(html, "");
    }

    public String renderHtml(String html, String contextPath) {
        if (html == null) {
            return null;
        }
        String versionParam = "v=" + urlEncode(buildId);
        String rendered = SCRIPT_PATTERN.matcher(html).replaceAll(match ->
                match.group(1) + addVersionParam(match.group(2), versionParam) + match.group(3)
        );
        rendered = LINK_PATTERN.matcher(rendered).replaceAll(match ->
                match.group(1) + addVersionParam(match.group(2), versionParam) + match.group(3)
        );
        rendered = injectBuildInfo(rendered, contextPath);
        return rendered;
    }

    public void setNoStoreHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
    }

    private String readVersionFromVersionJs() {
        try (InputStream is = getClass().getResourceAsStream("/static/js/version.js")) {
            if (is == null) {
                return "unknown";
            }
            String content = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            Matcher matcher = VERSION_PATTERN.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "unknown";
        } catch (IOException e) {
            return "error";
        }
    }

    private static String buildBuildId(String version, long startupEpochMs) {
        String safeVersion = sanitizeVersion(version);
        if (safeVersion.isBlank()) {
            safeVersion = "unknown";
        }
        return safeVersion + "-" + Long.toString(startupEpochMs, 36);
    }

    private static String sanitizeVersion(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String injectBuildInfo(String html, String contextPath) {
        String script = "<script>window.BILIUPFORJAVA_FRONTEND_BUILD_ID='" + escapeJs(buildId)
                + "';window.BILIUPFORJAVA_FRONTEND_VERSION='" + escapeJs(version)
                + "';window.BILIUPFORJAVA_CONTEXT_PATH='" + escapeJs(normalizeContextPath(contextPath))
                + "';</script>";
        Matcher matcher = HEAD_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.replaceFirst("<head>\n    " + Matcher.quoteReplacement(script));
        }
        return script + html;
    }

    private static String normalizeContextPath(String value) {
        if (value == null || value.isBlank() || "/".equals(value)) {
            return "";
        }
        String normalized = value.startsWith("/") ? value : "/" + value;
        return normalized.replaceAll("/+$", "");
    }

    private static String addVersionParam(String url, String versionParam) {
        if (url == null || url.isBlank() || shouldSkipVersionParam(url)) {
            return url;
        }
        if (url.matches(".*([?&])v=[^&]*.*")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + versionParam;
    }

    private static boolean shouldSkipVersionParam(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("//")
                || lower.startsWith("data:")
                || lower.startsWith("blob:")
                || lower.startsWith("javascript:")
                || lower.startsWith("#")
                || lower.contains("favicon");
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String escapeJs(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
