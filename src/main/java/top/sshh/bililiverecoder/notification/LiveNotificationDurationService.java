package top.sshh.bililiverecoder.notification;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordEventDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class LiveNotificationDurationService {

    private static final DateTimeFormatter LIVE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneId PLATFORM_ZONE = ZoneId.of("Asia/Shanghai");

    private final Map<String, Timing> timings = new ConcurrentHashMap<>();
    private final Clock clock;

    public LiveNotificationDurationService() {
        this(Clock.systemUTC());
    }

    LiveNotificationDurationService(Clock clock) {
        this.clock = clock;
    }

    public void streamStarted(RecordEventDTO event) {
        if (!hasRoom(event)) return;
        EventTime time = eventTime(event);
        String sessionId = StringUtils.trimToNull(event.getEventData().getSessionId());
        String eventId = StringUtils.firstNonBlank(event.getEventId(), event.getId());
        timings.compute(event.getEventData().getRoomId(), (roomId, current) -> {
            if (current != null) {
                if (eventId != null && eventId.equals(current.startEventId)) return current;
                if (current.lastEnd != null && !time.at().isAfter(current.lastEnd)) return current;
                if (current.end == null && current.start != null) {
                    if (current.knownStart && time.at().isBefore(current.start)) return current;
                    boolean sameSession = sessionId != null && sessionId.equals(current.sessionId);
                    boolean joinsPlatformSession = current.sessionId == null && current.platformStart != null
                            && (time.estimated() || current.lastObservedLive != null
                            && !time.at().isAfter(current.lastObservedLive));
                    if (sameSession || joinsPlatformSession) {
                        if (!current.knownStart) {
                            current.start = time.at();
                            current.knownStart = true;
                            current.estimatedStart = time.estimated();
                        }
                        current.sessionId = sessionId;
                        current.startEventId = eventId;
                        return current;
                    }
                }
            }
            Timing next = newTiming(current, time.at(), true);
            next.estimatedStart = time.estimated();
            next.sessionId = sessionId;
            next.startEventId = eventId;
            return next;
        });
    }

    public void streamEnded(RecordEventDTO event) {
        if (!hasRoom(event)) return;
        EventTime time = eventTime(event);
        String sessionId = StringUtils.trimToNull(event.getEventData().getSessionId());
        timings.compute(event.getEventData().getRoomId(), (roomId, current) -> {
            if (current == null) current = new Timing();
            if (current.start != null && time.at().isBefore(current.start)) return current;
            if (sessionId != null && current.sessionId != null && !sessionId.equals(current.sessionId)) return current;
            if (current.end != null && (!current.estimatedEnd || time.estimated())) return current;
            if (current.end != null && time.at().isAfter(current.end)) return current;
            current.end = time.at();
            current.lastEnd = time.at();
            current.estimatedEnd = time.estimated();
            return current;
        });
    }

    // 这里只记录通知要用的时间，不改房间状态，也不负责触发通知
    public String observeRoom(String roomId, boolean live, String liveTime,
                              Instant observedAt, Boolean previouslyLive) {
        if (StringUtils.isBlank(roomId)) return null;
        Instant platformStart = live ? parsePlatformStart(liveTime, observedAt) : null;
        AtomicReference<String> durationText = new AtomicReference<>();
        timings.compute(roomId, (key, current) -> {
            if (live) {
                current = observeLive(current, platformStart, observedAt, previouslyLive);
            } else if (current != null) {
                boolean staleObservation = (current.start != null && observedAt.isBefore(current.start))
                        || (current.lastObservedLive != null && observedAt.isBefore(current.lastObservedLive));
                if (!staleObservation && current.end == null) {
                    current.end = observedAt;
                    current.lastEnd = observedAt;
                    current.estimatedEnd = true;
                }
                durationText.set(formatDuration(current));
            }
            return current;
        });
        return durationText.get();
    }

    public void notificationConsumed(String roomId, Instant observedAt) {
        timings.computeIfPresent(roomId, (key, current) -> {
            if (current.end != null && !current.end.isAfter(observedAt)) {
                // 保留结束边界挡住迟到事件，清掉本场计时，下一场不能接着累计
                current.start = null;
                current.knownStart = false;
            }
            return current;
        });
    }

    private Timing observeLive(Timing current, Instant platformStart, Instant observedAt, Boolean previouslyLive) {
        if (current == null) {
            current = newTiming(null, platformStart == null ? observedAt : platformStart, platformStart != null);
        } else if (current.end != null) {
            if ((platformStart != null && platformStart.isAfter(current.lastEnd))
                    || (platformStart == null && !Boolean.TRUE.equals(previouslyLive) && observedAt.isAfter(current.lastEnd))) {
                current = newTiming(current, platformStart == null ? observedAt : platformStart, platformStart != null);
            } else {
                return current;
            }
        }
        if (observedAt.isBefore(current.start)) return current;
        boolean validPlatformStart = platformStart != null
                && (current.lastEnd == null || platformStart.isAfter(current.lastEnd))
                && (current.platformFloor == null || platformStart.isAfter(current.platformFloor));
        if (validPlatformStart) {
            if (current.platformStart != null && !platformStart.equals(current.platformStart)) {
                if (platformStart.isBefore(current.platformStart)) return current;
                current = newTiming(current, platformStart, true);
            }
            current.start = platformStart;
            current.platformStart = platformStart;
            current.knownStart = true;
            current.estimatedStart = false;
        }
        current.lastObservedLive = observedAt;
        return current;
    }

    private Timing newTiming(Timing previous, Instant start, boolean knownStart) {
        Timing next = new Timing();
        next.start = start;
        next.knownStart = knownStart;
        if (previous != null) {
            next.lastEnd = previous.lastEnd;
            next.platformFloor = previous.platformStart == null ? previous.platformFloor : previous.platformStart;
        }
        return next;
    }

    private Instant parsePlatformStart(String value, Instant observedAt) {
        if (StringUtils.isBlank(value)) return null;
        try {
            Instant start = LocalDateTime.parse(value.trim(), LIVE_TIME_FORMAT).atZone(PLATFORM_ZONE).toInstant();
            return start.isAfter(Instant.EPOCH) && !start.isAfter(observedAt) ? start : null;
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    private boolean hasRoom(RecordEventDTO event) {
        return event != null && event.getEventData() != null && StringUtils.isNotBlank(event.getEventData().getRoomId());
    }

    private EventTime eventTime(RecordEventDTO event) {
        Instant now = clock.instant();
        Date timestamp = event.getEventTimestamp() == null ? event.getDate() : event.getEventTimestamp();
        if (timestamp != null && timestamp.toInstant().isAfter(Instant.EPOCH) && !timestamp.toInstant().isAfter(now)) {
            return new EventTime(timestamp.toInstant(), false);
        }
        return new EventTime(now, true);
    }

    private String formatDuration(Timing timing) {
        if (!timing.knownStart || timing.start == null || timing.end == null || timing.end.isBefore(timing.start)) return null;
        long seconds = Duration.between(timing.start, timing.end).getSeconds();
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainingSeconds = seconds % 60;
        String text = hours > 0 ? "%d小时%d分%d秒".formatted(hours, minutes, remainingSeconds)
                : minutes > 0 ? "%d分%d秒".formatted(minutes, remainingSeconds) : "%d秒".formatted(remainingSeconds);
        return timing.estimatedStart || timing.estimatedEnd ? "约" + text : text;
    }

    private record EventTime(Instant at, boolean estimated) {}

    private static class Timing {
        private Instant start;
        private Instant end;
        private Instant platformStart;
        private Instant platformFloor;
        private Instant lastEnd;
        private Instant lastObservedLive;
        private String sessionId;
        private String startEventId;
        private boolean knownStart;
        private boolean estimatedStart;
        private boolean estimatedEnd;
    }
}
