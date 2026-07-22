package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogWebSocketTicketServiceTest {

    @Test
    void ticketCanOnlyBeConsumedOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        LogWebSocketTicketService service = new LogWebSocketTicketService(clock);

        String ticket = service.issue().ticket();

        assertTrue(service.consume(ticket));
        assertFalse(service.consume(ticket));
        assertFalse(service.consume("forged-ticket"));
    }

    @Test
    void expiredTicketIsRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        LogWebSocketTicketService service = new LogWebSocketTicketService(clock);

        String ticket = service.issue().ticket();
        clock.advanceMillis(30_001);

        assertFalse(service.consume(ticket));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceMillis(long millis) {
            instant = instant.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
