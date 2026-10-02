package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordEventDTO;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxEvent;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxStatusDto;
import top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO;
import top.sshh.bililiverecoder.repo.RecordWebhookInboxRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RecordWebhookInboxService {
    private static final int MAX_ATTEMPTS = 6;
    private final RecordWebhookInboxRepository repository;
    private final RecordEventFactory eventFactory;
    private final WebhookEventDispatcher dispatcher;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();
    private final int batchSize;
    @Autowired(required = false)
    private DatabaseMaintenanceService databaseMaintenanceService;

    public RecordWebhookInboxService(RecordWebhookInboxRepository repository,
                                    RecordEventFactory eventFactory,
                                    WebhookEventDispatcher dispatcher,
                                    @Value("${record.webhook.inbox-batch-size:100}") int batchSize) {
        this.repository = repository;
        this.eventFactory = eventFactory;
        this.dispatcher = dispatcher;
        this.batchSize = Math.max(1, batchSize);
    }

    @PostConstruct
    public void recoverInterruptedEvents() {
        int recovered = repository.recoverInterrupted(LocalDateTime.now());
        if (recovered > 0) {
            log.warn("[BLR] {}", LogKvs.event("Webhook.Inbox.Recovered")
                    .add("count", recovered));
        }
    }

    public Acceptance accept(String payload, String lockKey, long delayMs, String source, String eventType) {
        return accept(payload, lockKey, delayMs, source, eventType, null);
    }

    public Acceptance accept(String payload, String lockKey, long delayMs, String source,
                             String eventType, String upstreamEventId) {
        String normalizedSource = source == null ? "unknown" : source;
        String normalizedLockKey = digest(lockKey == null ? "webhook:unknown" : lockKey);
        String identity = upstreamEventId == null || upstreamEventId.isBlank()
                ? "payload\n" + payload
                : "event\n" + eventType + "\n" + upstreamEventId.trim();
        String eventKey = digest(normalizedSource + "\n" + normalizedLockKey + "\n" + identity);
        Optional<RecordWebhookInboxEvent> previous = repository.findByEventKey(eventKey);
        if (previous.isPresent()) return new Acceptance(previous.get().getId(), true, previous.get().getState());
        // 兼容升级前未按房间区分的事件指纹，只复用属于同一房间的旧事件
        String legacyEventKey = digest(source + "\n" + identity);
        Optional<RecordWebhookInboxEvent> legacy = repository.findByEventKey(legacyEventKey);
        if (legacy.isPresent() && normalizedLockKey.equals(legacy.get().getLockKey())) {
            return new Acceptance(legacy.get().getId(), true, legacy.get().getState());
        }

        LocalDateTime now = LocalDateTime.now();
        RecordWebhookInboxEvent event = new RecordWebhookInboxEvent();
        event.setEventKey(eventKey);
        event.setLockKey(normalizedLockKey);
        event.setEventType(eventType);
        event.setSource(normalizedSource);
        event.setDelayMs(Math.max(0L, delayMs));
        event.setPayload(payload);
        event.setState("PENDING");
        event.setAttemptCount(0);
        event.setNextAttemptAt(now);
        event.setReceivedAt(now);
        event.setUpdatedAt(now);
        try {
            event = repository.saveAndFlush(event);
        } catch (DataIntegrityViolationException duplicate) {
            RecordWebhookInboxEvent existing = repository.findByEventKey(eventKey).orElseThrow(() -> duplicate);
            return new Acceptance(existing.getId(), true, existing.getState());
        }
        return new Acceptance(event.getId(), false, event.getState());
    }

    public List<RecordWebhookInboxStatusDto> recent(int limit) {
        int pageSize = Math.max(1, Math.min(200, limit));
        return repository.findAll(PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "id"))).stream()
                .map(RecordWebhookInboxStatusDto::from).toList();
    }

    @Transactional
    public boolean retryFailed(Long id) {
        RecordWebhookInboxEvent event = repository.findById(id).orElse(null);
        if (event == null || !"FAILED".equals(event.getState())) return false;
        event.setState("PENDING");
        event.setAttemptCount(0);
        event.setNextAttemptAt(LocalDateTime.now());
        event.setUpdatedAt(LocalDateTime.now());
        event.setErrorMessage(null);
        repository.save(event);
        return true;
    }

    @Scheduled(fixedDelayString = "${record.webhook.inbox-poll-ms:1000}")
    public void dispatchDueEvents() {
        if (databaseMaintenanceService != null && databaseMaintenanceService.isMaintenanceActive()) return;
        List<RecordWebhookInboxEvent> due = repository.findDueHeadEvents(
                "PENDING", LocalDateTime.now(), PageRequest.of(0, batchSize));
        for (RecordWebhookInboxEvent candidate : due) {
            if (candidate.getId() == null || !inFlight.add(candidate.getId())) continue;
            dispatch(candidate.getId());
        }
    }

    @Scheduled(cron = "0 20 4 * * *")
    public void pruneTerminalEvents() {
        if (databaseMaintenanceService != null && databaseMaintenanceService.isMaintenanceActive()) return;
        repository.deleteOldTerminal(LocalDateTime.now().minusDays(30));
    }

    private void dispatch(Long id) {
        RecordWebhookInboxEvent event = repository.findById(id).orElse(null);
        if (event == null || !"PENDING".equals(event.getState())) {
            inFlight.remove(id);
            return;
        }
        event.setState("PROCESSING");
        event.setAttemptCount(event.getAttemptCount() + 1);
        event.setUpdatedAt(LocalDateTime.now());
        event.setErrorMessage(null);
        event = repository.save(event);
        RecordWebhookInboxEvent claimed = event;
        boolean accepted;
        try {
            accepted = dispatcher.submit(claimed.getLockKey(), claimed.getDelayMs(), () -> process(claimed.getId()));
        } catch (RuntimeException error) {
            retryAdmission(claimed.getId(), error);
            return;
        }
        if (!accepted) retryAdmission(claimed.getId(), null);
    }

    private void process(Long id) {
        RecordWebhookInboxEvent event = repository.findById(id).orElse(null);
        if (event == null) {
            inFlight.remove(id);
            return;
        }
        try {
            if ("blrec-native".equals(event.getSource())) {
                BlrecEventDTO blrecEvent = JSON.parseObject(event.getPayload(), BlrecEventDTO.class);
                String roomId = resolveBlrecRoomId(blrecEvent);
                if (databaseMaintenanceService == null) {
                    throw new IllegalStateException("录播 webhook 事件处理服务不可用");
                }
                if (!databaseMaintenanceService.dispatchBlrecEvent(roomId, blrecEvent)) {
                    event.setState("IGNORED");
                    event.setNextAttemptAt(null);
                    event.setUpdatedAt(LocalDateTime.now());
                    repository.save(event);
                    return;
                }
            } else {
                RecordEventDTO recordEvent = JSON.parseObject(event.getPayload(), RecordEventDTO.class);
                if (recordEvent == null) throw new IllegalArgumentException("Webhook 内容无法解析");
                if (eventFactory.isUnsupportedEvent(recordEvent)) {
                    event.setState("IGNORED");
                    event.setErrorMessage(null);
                    event.setNextAttemptAt(null);
                    event.setUpdatedAt(LocalDateTime.now());
                    repository.save(event);
                    log.info("[BLR] {}", LogKvs.event("Webhook.EventIgnored")
                            .add("eventId", id).add("eventType", event.getEventType())
                            .add("source", event.getSource()));
                    return;
                }
                eventFactory.processing(recordEvent);
            }
            event.setState("DONE");
            event.setErrorMessage(null);
            event.setNextAttemptAt(null);
            event.setUpdatedAt(LocalDateTime.now());
            repository.save(event);
        } catch (Exception error) {
            event.setErrorMessage(truncate(error.getClass().getSimpleName() + ": " + error.getMessage(), 2000));
            event.setUpdatedAt(LocalDateTime.now());
            if (event.getAttemptCount() >= MAX_ATTEMPTS) {
                event.setState("FAILED");
                event.setNextAttemptAt(null);
            } else {
                event.setState("PENDING");
                event.setNextAttemptAt(LocalDateTime.now().plusSeconds(retryDelaySeconds(event.getAttemptCount())));
            }
            repository.save(event);
            log.error("[BLR] {}", LogKvs.event("Webhook.Inbox.ProcessFailed")
                    .add("eventId", id).add("source", event.getSource()).add("eventType", event.getEventType())
                    .add("attempt", event.getAttemptCount()).add("state", event.getState())
                    .addIfNotBlank("err", error.getMessage()).add("ex", error.getClass().getSimpleName()), error);
        } finally {
            inFlight.remove(id);
        }
    }

    private void retryAdmission(Long id, RuntimeException error) {
        RecordWebhookInboxEvent event = repository.findById(id).orElse(null);
        if (event != null) {
            event.setState("PENDING");
            event.setAttemptCount(Math.max(0, event.getAttemptCount() - 1));
            event.setNextAttemptAt(LocalDateTime.now().plusSeconds(2));
            event.setUpdatedAt(LocalDateTime.now());
            if (error != null) event.setErrorMessage(truncate(error.getMessage(), 2000));
            repository.save(event);
        }
        inFlight.remove(id);
    }

    private static long retryDelaySeconds(int attempt) {
        return switch (attempt) {
            case 1 -> 5;
            case 2 -> 30;
            case 3 -> 120;
            case 4 -> 600;
            default -> 1800;
        };
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("无法计算 webhook 指纹", error);
        }
    }

    private static String resolveBlrecRoomId(BlrecEventDTO event) {
        if (event != null && event.getData() != null) {
            if (event.getData().getRoomInfo() != null && event.getData().getRoomInfo().getRoomId() != null) {
                return String.valueOf(event.getData().getRoomInfo().getRoomId());
            }
            if (event.getData().getRoomId() != null) return String.valueOf(event.getData().getRoomId());
        }
        throw new IllegalArgumentException("blrec webhook 缺少 roomId");
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record Acceptance(Long eventId, boolean alreadyQueued, String state) {}
}
