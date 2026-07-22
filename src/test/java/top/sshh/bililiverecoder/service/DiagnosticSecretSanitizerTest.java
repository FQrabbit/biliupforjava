package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSecretSanitizerTest {

    @Test
    void masksKnownAndInlineCredentialsButKeepsNonSensitiveSettings() {
        DiagnosticSecretSanitizer sanitizer = new DiagnosticSecretSanitizer(List.of("real-cookie", "push-secret"));
        String text = sanitizer.sanitizeText("Cookie=real-cookie Authorization: Bearer abc token=xyz webhook=push-secret");

        assertFalse(text.contains("real-cookie"));
        assertFalse(text.contains("push-secret"));
        assertFalse(text.contains("abc"));
        assertTrue(text.contains("[REDACTED]"));

        Object sanitized = sanitizer.sanitizeStructured(Map.of(
                "uploadLine", "ws",
                "cookie", "real-cookie",
                "roomTemplate", "{title}"
        ), null);
        String jsonLike = String.valueOf(sanitized);
        assertTrue(jsonLike.contains("uploadLine=ws"));
        assertTrue(jsonLike.contains("roomTemplate={title}"));
        assertFalse(jsonLike.contains("real-cookie"));
    }
}
