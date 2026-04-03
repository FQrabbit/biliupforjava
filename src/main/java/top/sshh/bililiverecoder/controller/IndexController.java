package top.sshh.bililiverecoder.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/")
public class IndexController {

    private static final Pattern SCRIPT_PATTERN = Pattern.compile(
            "(<script[^>]*src=[\"'])([^\"']+)([\"'][^>]*>)"
    );
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(<link[^>]*href=[\"'])([^\"']+)([\"'][^>]*>)"
    );

    @GetMapping(value = "/index.html", produces = MediaType.TEXT_HTML_VALUE)
    public void getIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String version = readVersionFromFile();

        ClassPathResource resource = new ClassPathResource("static/index.html");
        if (!resource.exists()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        content = injectVersion(content, version);

        response.setContentType("text/html;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        response.getWriter().write(content);
    }

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
    public void getRoot(HttpServletRequest request, HttpServletResponse response) throws IOException {
        getIndex(request, response);
    }

    private String injectVersion(String html, String version) {
        String versionParam = "?v=" + escapeForHtml(version);

        html = SCRIPT_PATTERN.matcher(html).replaceAll(match ->
            match.group(1) + addVersionParam(match.group(2), versionParam) + match.group(3)
        );

        html = LINK_PATTERN.matcher(html).replaceAll(match ->
            match.group(1) + addVersionParam(match.group(2), versionParam) + match.group(3)
        );

        return html;
    }

    private String addVersionParam(String url, String versionParam) {
        if (url.startsWith("http") || url.startsWith("//") || url.contains("?") || url.contains("favicon")) {
            return url;
        }
        return url + versionParam;
    }

    private String escapeForHtml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }

    private String readVersionFromFile() {
        try (InputStream is = getClass().getResourceAsStream("/static/js/version.js")) {
            if (is == null) {
                return "unknown";
            }
            String content = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
            Pattern versionPattern = Pattern.compile(
                    "window\\.BILIUPFORJAVA_VERSION\\s*=\\s*['\"]([^'\"]+)['\"]"
            );
            Matcher matcher = versionPattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
            return "unknown";
        } catch (IOException e) {
            return "error";
        }
    }
}
