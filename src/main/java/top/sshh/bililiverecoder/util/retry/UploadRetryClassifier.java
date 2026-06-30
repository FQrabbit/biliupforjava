package top.sshh.bililiverecoder.util.retry;

import org.apache.commons.lang3.StringUtils;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UploadRetryClassifier {

    public static final String REMOTE_THROTTLED = "REMOTE_THROTTLED";
    public static final String RATE_LIMIT = "RATE_LIMIT";
    public static final String GATEWAY_5XX = "GATEWAY_5XX";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String DNS = "DNS";
    public static final String NETWORK_IO = "NETWORK_IO";
    public static final String LOW_SPEED = "LOW_SPEED";
    public static final String HTTP_5XX = "HTTP_5XX";
    public static final String AUTH = "AUTH";
    public static final String HTTP_4XX = "HTTP_4XX";
    public static final String UNKNOWN = "UNKNOWN";

    private static final Pattern HTTP_CODE_PATTERN = Pattern.compile("\\bcode\\s*=\\s*(\\d{3})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern XML_CODE_PATTERN = Pattern.compile("<Code>\\s*([^<]+?)\\s*</Code>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private UploadRetryClassifier() {
    }

    public static UploadRetryAssessment assess(Throwable throwable, String errMsg) {
        Throwable root = rootCause(throwable);
        String message = normalize(StringUtils.defaultString(errMsg));
        String combined = normalize(buildCombinedMessage(throwable, message));
        String rootName = root == null ? "" : root.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String remoteCode = extractRemoteCode(combined);

        if (containsAny(combined, "UserNetworkTooSlow", "user network is too slow", "network too slow", "usernetworktooslow")) {
            return new UploadRetryAssessment(true, REMOTE_THROTTLED, "平台限速，等待重试", remoteCode, combined);
        }
        if (containsAny(combined, "429", "rate limit", "too many requests", "限流", "频控")) {
            return new UploadRetryAssessment(true, RATE_LIMIT, "平台限流，等待重试", remoteCode, combined);
        }
        if (containsAny(combined, "500", "502", "503", "504", "gateway")) {
            return new UploadRetryAssessment(true, GATEWAY_5XX, "平台网关异常，等待重试", remoteCode, combined);
        }
        if (containsAny(combined, "low upload speed")) {
            return new UploadRetryAssessment(true, LOW_SPEED, "上传速率过低，等待重试", remoteCode, combined);
        }
        if (root instanceof UnknownHostException || rootName.contains("unknownhost")) {
            return new UploadRetryAssessment(true, DNS, "DNS 解析失败，等待重试", remoteCode, combined);
        }
        if (root instanceof SocketTimeoutException
                || root instanceof InterruptedIOException
                || rootName.contains("timeout")) {
            return new UploadRetryAssessment(true, TIMEOUT, "上传超时，等待重试", remoteCode, combined);
        }
        if (root instanceof ConnectException
                || root instanceof NoRouteToHostException
                || root instanceof SocketException
                || rootName.contains("ssl")
                || containsAny(combined, "connection reset", "broken pipe", "netty upload failed")) {
            return new UploadRetryAssessment(true, NETWORK_IO, "网络抖动，等待重试", remoteCode, combined);
        }
        if (containsAny(combined, "401", "403", "forbidden", "unauthorized")) {
            return new UploadRetryAssessment(false, AUTH, "平台鉴权失败", remoteCode, combined);
        }
        if (StringUtils.isNotBlank(remoteCode)) {
            if (remoteCode.startsWith("5")) {
                return new UploadRetryAssessment(true, HTTP_5XX, "平台返回 5xx，等待重试", remoteCode, combined);
            }
            if (remoteCode.startsWith("4")) {
                return new UploadRetryAssessment(false, HTTP_4XX, "平台返回 4xx", remoteCode, combined);
            }
        }
        if (containsAny(combined, "5xx")) {
            return new UploadRetryAssessment(true, HTTP_5XX, "平台返回 5xx，等待重试", remoteCode, combined);
        }
        if (containsAny(combined, "4xx", "400")) {
            return new UploadRetryAssessment(false, HTTP_4XX, "平台返回 4xx", remoteCode, combined);
        }
        return new UploadRetryAssessment(true, UNKNOWN, "上传失败，等待重试", remoteCode, combined);
    }

    public static String extractRemoteCode(String errMsg) {
        String message = normalize(StringUtils.defaultString(errMsg));
        Matcher xmlMatcher = XML_CODE_PATTERN.matcher(message);
        if (xmlMatcher.find()) {
            return StringUtils.trimToEmpty(xmlMatcher.group(1));
        }
        Matcher codeMatcher = HTTP_CODE_PATTERN.matcher(message);
        if (codeMatcher.find()) {
            return codeMatcher.group(1);
        }
        return "";
    }

    public static boolean isRetryableCategory(String category) {
        if (StringUtils.isBlank(category)) {
            return false;
        }
        return switch (category) {
            case REMOTE_THROTTLED, RATE_LIMIT, GATEWAY_5XX, TIMEOUT, DNS, NETWORK_IO, LOW_SPEED, HTTP_5XX, UNKNOWN -> true;
            default -> false;
        };
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null && cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor;
    }

    private static String buildCombinedMessage(Throwable throwable, String errMsg) {
        StringBuilder sb = new StringBuilder();
        appendIfNotBlank(sb, errMsg);
        Throwable current = throwable;
        while (current != null) {
            appendIfNotBlank(sb, current.getMessage());
            current = current.getCause();
        }
        return sb.toString();
    }

    private static void appendIfNotBlank(StringBuilder sb, String value) {
        if (StringUtils.isBlank(value)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value);
    }

    private static boolean containsAny(String text, String... needles) {
        if (StringUtils.isBlank(text) || needles == null || needles.length == 0) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (StringUtils.isNotBlank(needle) && lower.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    public record UploadRetryAssessment(boolean retryable, String category, String userMessage, String remoteCode, String evidence) {
    }
}
