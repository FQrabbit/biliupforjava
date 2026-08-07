package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LogWebSocketTicketService {

    private static final long TTL_MILLIS = 30_000L;
    private static final int MAX_TICKETS = 1024;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Long> tickets = new ConcurrentHashMap<>();
    private final Clock clock;

    public LogWebSocketTicketService() {
        this(Clock.systemUTC());
    }

    LogWebSocketTicketService(Clock clock) {
        this.clock = clock;
    }

    public Ticket issue() {
        cleanup();
        if (tickets.size() >= MAX_TICKETS) {
            throw new IllegalStateException("Too many pending websocket tickets");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expiresAt = clock.millis() + TTL_MILLIS;
        tickets.put(value, expiresAt);
        return new Ticket(value, expiresAt);
    }

    public boolean consume(String ticket) {
        if (ticket == null || ticket.isBlank()) return false;
        Long expiresAt = tickets.remove(ticket);
        return expiresAt != null && expiresAt >= clock.millis();
    }

    private void cleanup() {
        long now = clock.millis();
        tickets.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    public record Ticket(String ticket, long expiresAt) {
    }
}
