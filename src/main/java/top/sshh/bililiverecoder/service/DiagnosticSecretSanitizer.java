package top.sshh.bililiverecoder.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** 把诊断包中真正的凭据替换掉，其他排障字段保持原样 */
public final class DiagnosticSecretSanitizer {

    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)(authorization\\s*[:=]\\s*)(Basic|Bearer)\\s+[^\\s,;|]+?");
    private static final Pattern KEY_VALUE = Pattern.compile("(?i)((?:password|passwd|pwd|cookie|token|secret|authorization|api[-_]?key|access[-_]?key|refresh[-_]?token|send[-_]?key|webhook)\\s*[:=]\\s*)([^,;|\\s]+)");
    private static final Pattern JSON_VALUE = Pattern.compile("(?i)(\"(?:password|passwd|pwd|cookie|token|secret|authorization|api[-_]?key|access[-_]?key|refresh[-_]?token|send[-_]?key|webhook)\"\\s*:\\s*\")(?:[^\"]*)(\")");
    private static final Pattern URL_QUERY = Pattern.compile("(?i)([?&](?:token|key|secret|auth|signature)=)[^&\\s]+?");

    private final List<String> knownSecrets;
    private final AtomicInteger redacted = new AtomicInteger();

    public DiagnosticSecretSanitizer(Collection<String> secrets) {
        this.knownSecrets = secrets.stream()
                .filter(value -> value != null && value.trim().length() >= 3)
                .map(String::trim)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public String sanitizeText(String input) {
        if (input == null) return null;
        String result = input;
        for (String secret : knownSecrets) {
            if (result.contains(secret)) {
                result = result.replace(secret, mark());
            }
        }
        result = AUTHORIZATION.matcher(result).replaceAll("$1$2 " + mark());
        result = KEY_VALUE.matcher(result).replaceAll("$1" + mark());
        result = JSON_VALUE.matcher(result).replaceAll("$1" + mark() + "$2");
        result = URL_QUERY.matcher(result).replaceAll("$1" + mark());
        return result;
    }

    public Object sanitizeStructured(Object value, String key) {
        if (value == null) return null;
        if (isSensitiveKey(key)) {
            return mark();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                result.put(childKey, sanitizeStructured(entry.getValue(), childKey));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) result.add(sanitizeStructured(item, key));
            return result;
        }
        return value instanceof String text ? sanitizeText(text) : value;
    }

    public int redactedCount() {
        return redacted.get();
    }

    private String mark() {
        redacted.incrementAndGet();
        return "[REDACTED]";
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.replaceAll("[^a-zA-Z]", "").toLowerCase(Locale.ROOT);
        return normalized.contains("password") || normalized.contains("passwd") || normalized.equals("pwd")
                || normalized.contains("cookie") || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("authorization") || normalized.contains("apikey")
                || normalized.contains("accesskey") || normalized.contains("sendkey")
                || normalized.contains("webhook") || normalized.equals("wxuid");
    }
}
