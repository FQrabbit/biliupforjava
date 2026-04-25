package top.sshh.bililiverecoder.util.bili.upload;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class MultipartDebugSupport {

    private static volatile Boolean CONFIG_ENABLED = null;

    private MultipartDebugSupport() {
    }

    public static boolean isEnabled() {
        if (CONFIG_ENABLED != null) {
            return CONFIG_ENABLED;
        }
        // Native-image 下静态常量可能在构建期被固化，这里每次运行时动态读取
        // JVM 参数 / 环境变量，确保 -Dblr.multipart.debug=true 能即时生效。
        return resolveEnabled();
    }

    public static void setEnabledFromConfig(Boolean enabled) {
        CONFIG_ENABLED = enabled;
    }

    public static boolean parseTruthy(String raw) {
        return isTruthy(raw);
    }

    private static boolean resolveEnabled() {
        String fromProperty = System.getProperty("blr.multipart.debug");
        if (isTruthy(fromProperty)) {
            return true;
        }
        String fromCompatProperty = System.getProperty("record.upload.multipart-debug");
        if (isTruthy(fromCompatProperty)) {
            return true;
        }
        String fromEnv = System.getenv("BLR_MULTIPART_DEBUG");
        return isTruthy(fromEnv);
    }

    private static boolean isTruthy(String raw) {
        if (StringUtils.isBlank(raw)) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return "1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value) || "y".equals(value);
    }

    public static String tokenHint(String token) {
        if (StringUtils.isBlank(token)) {
            return "";
        }
        String value = token.trim();
        if (value.length() <= 14) {
            return "****#" + digest8(value);
        }
        return value.substring(0, 6) + "..." + value.substring(value.length() - 6) + "#" + digest8(value);
    }

    public static String abbreviate(String raw, int maxLen) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r", " ").replace("\n", " ");
        if (text.length() <= maxLen) {
            return text;
        }
        if (maxLen <= 3) {
            return text.substring(0, maxLen);
        }
        return text.substring(0, maxLen - 3) + "...";
    }

    public static String hostOfUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return StringUtils.defaultString(uri.getHost());
        } catch (Exception e) {
            return "";
        }
    }

    public static String pathOfUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return StringUtils.defaultString(uri.getPath());
        } catch (Exception e) {
            return "";
        }
    }

    public static String queryValue(String url, String key) {
        if (StringUtils.isBlank(url) || StringUtils.isBlank(key)) {
            return "";
        }
        Map<String, String> query = queryMap(url);
        return StringUtils.defaultString(query.get(key));
    }

    public static int partNumberFromUrl(String url) {
        String byQuery = queryValue(url, "partNumber");
        if (StringUtils.isBlank(byQuery)) {
            byQuery = queryValue(url, "partnumber");
        }
        if (StringUtils.isNotBlank(byQuery)) {
            try {
                return Integer.parseInt(byQuery);
            } catch (Exception ignore) {
                // fallback to path pattern
            }
        }
        if (StringUtils.isBlank(url)) {
            return -1;
        }
        String path = pathOfUrl(url);
        int idxPart = path.lastIndexOf(".part");
        if (idxPart > 0) {
            int dash = path.lastIndexOf('-', idxPart);
            if (dash >= 0 && dash + 1 < idxPart) {
                String tail = path.substring(dash + 1, idxPart);
                try {
                    return Integer.parseInt(tail);
                } catch (Exception ignore) {
                    return -1;
                }
            }
        }
        return -1;
    }

    public static String uploadIdFromUrl(String url) {
        String value = queryValue(url, "uploadId");
        if (StringUtils.isBlank(value)) {
            value = queryValue(url, "uploadid");
        }
        return value;
    }

    private static Map<String, String> queryMap(String url) {
        Map<String, String> map = new HashMap<>();
        try {
            URI uri = URI.create(url);
            String query = uri.getRawQuery();
            if (StringUtils.isBlank(query)) {
                return map;
            }
            for (String kv : query.split("&")) {
                String[] pair = kv.split("=", 2);
                if (pair.length == 2) {
                    map.put(pair[0], java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                } else if (pair.length == 1) {
                    map.put(pair[0], "");
                }
            }
        } catch (Exception ignore) {
            // no-op
        }
        return map;
    }

    private static String digest8(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 8);
        } catch (Exception e) {
            return "00000000";
        }
    }
}
