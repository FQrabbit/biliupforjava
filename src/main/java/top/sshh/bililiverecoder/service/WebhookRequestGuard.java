package top.sshh.bililiverecoder.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class WebhookRequestGuard {
    private static final long RATE_WINDOW_MS = 60_000L;
    private final String token;
    private final int maxRequestsPerMinute;
    private final boolean allowQueryToken;
    private final ConcurrentHashMap<String, RateWindow> requestWindows = new ConcurrentHashMap<>();

    @Autowired
    public WebhookRequestGuard(@Value("${record.webhook.token:}") String token,
                               @Value("${record.webhook.max-requests-per-minute:0}") int maxRequestsPerMinute,
                               @Value("${record.webhook.allow-query-token:false}") boolean allowQueryToken) {
        this.token = token == null ? "" : token.trim();
        this.maxRequestsPerMinute = Math.max(0, maxRequestsPerMinute);
        this.allowQueryToken = allowQueryToken;
    }

    public WebhookRequestGuard(String token) {
        this(token, 0, false);
    }

    public String rejectionReason(HttpServletRequest request) {
        if (!allowRequest(request.getRemoteAddr())) return "RATE_LIMITED";
        if (!authorized(request)) return "UNAUTHORIZED";
        return null;
    }

    private boolean authorized(HttpServletRequest request) {
        if (token.isBlank()) {
            return true;
        }
        String supplied = request.getHeader("X-Record-Webhook-Token");
        if (supplied == null || supplied.isBlank()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                supplied = authorization.substring(7).trim();
            }
        }
        if ((supplied == null || supplied.isBlank()) && allowQueryToken) supplied = request.getParameter("token");
        if (supplied == null) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }

    private boolean allowRequest(String remoteAddress) {
        if (maxRequestsPerMinute <= 0) return true;
        String key = remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
        long now = System.currentTimeMillis();
        RateWindow window = requestWindows.compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAtMs >= RATE_WINDOW_MS) return new RateWindow(now);
            current.count.incrementAndGet();
            return current;
        });
        if (requestWindows.size() > 2048) {
            requestWindows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMs >= RATE_WINDOW_MS);
        }
        return window.count.get() <= maxRequestsPerMinute;
    }

    private static final class RateWindow {
        private final long startedAtMs;
        private final AtomicInteger count = new AtomicInteger(1);

        private RateWindow(long startedAtMs) {
            this.startedAtMs = startedAtMs;
        }
    }
}
