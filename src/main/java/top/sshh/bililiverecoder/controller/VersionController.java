package top.sshh.bililiverecoder.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api")
public class VersionController {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "window\\.BILIUPFORJAVA_VERSION\\s*=\\s*['\"]([^'\"]+)['\"]"
    );

    @GetMapping("/version")
    public ResponseEntity<Map<String, Object>> getVersion() {
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("version", readVersionFromFile());
        result.put("timestamp", System.currentTimeMillis());
        HttpHeaders headers = new HttpHeaders();
        headers.set("Cache-Control", "no-cache, no-store, must-revalidate");
        headers.set("Pragma", "no-cache");
        headers.set("Expires", "0");
        return ResponseEntity.ok().headers(headers).body(result);
    }

    private String readVersionFromFile() {
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

    @GetMapping("/version/check")
    public String checkVersion() {
        String currentVersion = readVersionFromFile();
        String storedVersion = readStoredVersion();

        if ("unknown".equals(currentVersion) || "error".equals(currentVersion)) {
            return "{\"needRefresh\": false, \"error\": true}";
        }

        if ("unknown".equals(storedVersion) || storedVersion == null) {
            return "{\"needRefresh\": false, \"currentVersion\": \"" + currentVersion + "\", \"storedVersion\": null}";
        }

        boolean needRefresh = !currentVersion.equals(storedVersion);
        return "{\"needRefresh\": " + needRefresh + ", \"currentVersion\": \"" + currentVersion + "\", \"storedVersion\": \"" + storedVersion + "\"}";
    }

    private String readStoredVersion() {
        return null;
    }
}
