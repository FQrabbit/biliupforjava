package top.sshh.bililiverecoder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.SystemConfig;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.NotificationChannelRepository;
import top.sshh.bililiverecoder.repo.NotificationRuleRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;
import top.sshh.bililiverecoder.util.ContainerUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class DiagnosticExportService {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(1, 3, 7, 14);
    private static final Pattern LOG_HEADER = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\|\\s*([A-Z]+)\\s*\\|");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final int CONTEXT_RECORDS = 30;
    private final Semaphore exportSlot = new Semaphore(1);

    private final LogArchiveService archiveService;
    private final RecordHistoryRepository historyRepository;
    private final RecordHistoryPartRepository partRepository;
    private final RecordRoomRepository roomRepository;
    private final BiliUserRepository userRepository;
    private final SystemConfigRepository systemConfigRepository;
    private final NotificationChannelRepository notificationChannelRepository;
    private final NotificationRuleRepository notificationRuleRepository;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    public DiagnosticExportService(LogArchiveService archiveService, RecordHistoryRepository historyRepository,
                                   RecordHistoryPartRepository partRepository, RecordRoomRepository roomRepository,
                                   BiliUserRepository userRepository, SystemConfigRepository systemConfigRepository,
                                   NotificationChannelRepository notificationChannelRepository,
                                   NotificationRuleRepository notificationRuleRepository,
                                   ObjectMapper objectMapper, Environment environment) {
        this.archiveService = archiveService;
        this.historyRepository = historyRepository;
        this.partRepository = partRepository;
        this.roomRepository = roomRepository;
        this.userRepository = userRepository;
        this.systemConfigRepository = systemConfigRepository;
        this.notificationChannelRepository = notificationChannelRepository;
        this.notificationRuleRepository = notificationRuleRepository;
        this.objectMapper = objectMapper;
        this.environment = environment;
    }

    public ExportPlan prepare(DiagnosticExportRequest request) {
        if (request == null || request.getMode() == null || !ALLOWED_DAYS.contains(request.getDays())) {
            throw new IllegalArgumentException("days 仅支持 1、3、7 或 14");
        }
        if (request.getNote() != null && request.getNote().length() > 2000) {
            throw new IllegalArgumentException("补充说明不能超过 2000 个字符");
        }
        RecordHistory history = null;
        List<RecordHistoryPart> parts = List.of();
        RecordRoom room = null;
        LocalDateTime effectiveStart = null;
        LocalDateTime effectiveEnd = null;
        if (request.getMode() == DiagnosticExportRequest.Mode.HISTORY) {
            if (request.getHistoryId() == null) throw new IllegalArgumentException("指定稿件时必须选择稿件");
            history = historyRepository.findById(request.getHistoryId())
                    .orElseThrow(() -> new NoSuchElementException("稿件不存在"));
            parts = partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId());
            room = roomRepository.findByRoomId(history.getRoomId());
            effectiveStart = history.getStartTime();
            effectiveEnd = history.getEndTime() == null ? LocalDateTime.now() : history.getEndTime();
        }
        List<LogArchiveService.LogFile> fullWindow = history == null
                ? archiveService.filesForLastDays(request.getDays())
                : archiveService.filesForRange(effectiveStart, effectiveEnd);
        List<LogArchiveService.LogFile> relevantSource = history == null ? fullWindow : archiveService.listFiles();
        return new ExportPlan(request, history, parts, room, relevantSource, fullWindow, archiveService.inventory(),
                effectiveStart, effectiveEnd);
    }

    public boolean tryAcquire() {
        return exportSlot.tryAcquire();
    }

    public void release() {
        exportSlot.release();
    }

    public List<Map<String, Object>> searchHistories(String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase();
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<Map<String, Object>> result = new ArrayList<>();
        for (RecordHistory history : historyRepository.searchForDiagnostic(normalized,
                org.springframework.data.domain.PageRequest.of(0, Math.max(50, safeLimit)))) {
            if (matches(history, normalized)) result.add(historyOption(history));
            if (result.size() >= safeLimit) break;
        }
        if (normalized.matches("\\d+")) {
            try {
                historyRepository.findById(Long.parseLong(normalized)).ifPresent(history -> {
                    Map<String, Object> option = historyOption(history);
                    if (result.stream().noneMatch(item -> item.get("id").equals(option.get("id")))) result.add(0, option);
                });
            } catch (NumberFormatException ignored) {
                // 已通过正则过滤
            }
        }
        return result.stream().limit(safeLimit).toList();
    }

    public void write(ExportPlan plan, OutputStream outputStream) throws IOException {
        write(plan, outputStream, DiagnosticExportProgressService.ProgressReporter.NOOP);
    }

    public void write(ExportPlan plan, OutputStream outputStream,
                      DiagnosticExportProgressService.ProgressReporter progress) throws IOException {
        List<String> warnings = new ArrayList<>();
        DiagnosticExportProgressService.ProgressReporter reporter = progress == null
                ? DiagnosticExportProgressService.ProgressReporter.NOOP : progress;
        reporter.phase("PREPARING", "正在准备诊断包", 1);
        reporter.checkCancelled();
        Set<String> secrets = knownSecrets();
        DiagnosticSecretSanitizer sanitizer = new DiagnosticSecretSanitizer(secrets);
        List<String> entries = new ArrayList<>();
        ExportStats stats = new ExportStats();

        try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            writeJson(zip, entries, "system/runtime.json", runtimeInfo());
            writeJson(zip, entries, "system/log-storage.json", archiveService.inventory());
            reporter.phase("COLLECTING_CONTEXT", "正在收集配置和稿件状态", 3);
            if (plan.request().isIncludeSystemConfig()) {
                writeJson(zip, entries, "config/system.json", sanitize(systemConfigs(), sanitizer));
                writeJson(zip, entries, "config/accounts.json", sanitize(accounts(plan), sanitizer));
                writeJson(zip, entries, "config/notifications.json", sanitize(notifications(plan), sanitizer));
            }
            if (plan.request().isIncludeRoomConfig()) {
                writeJson(zip, entries, "config/rooms.json", sanitize(rooms(plan), sanitizer));
            }
            if (plan.history() != null) {
                writeJson(zip, entries, "target/history.json", sanitize(entityMap(plan.history()), sanitizer));
                writeJson(zip, entries, "target/parts.json", sanitize(entityMaps(plan.parts()), sanitizer));
            }
            if (plan.request().getNote() != null && !plan.request().getNote().isBlank()) {
                writeText(zip, entries, "user-note.txt", sanitizer.sanitizeText(plan.request().getNote()) + System.lineSeparator());
            }

            reporter.phase("ANALYZING_RELEVANT", "正在分析相关日志", 5);
            writeRelevant(zip, entries, plan, sanitizer, warnings, stats, reporter);
            if (plan.request().isIncludeFullLogs()) {
                reporter.phase("PACKING_FULL_LOGS", "正在整理完整时段日志", 5);
                writeFullWindow(zip, entries, plan.fullWindow(), sanitizer, warnings, reporter);
            }

            reporter.phase("FINALIZING", "正在写入诊断包清单", 98);
            reporter.checkCancelled();
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("mode", plan.request().getMode());
            summary.put("days", plan.request().getDays());
            summary.put("requestedOccurredAt", plan.request().getOccurredAt());
            summary.put("targetRangeStart", plan.effectiveStart());
            summary.put("targetRangeEnd", plan.effectiveEnd());
            summary.put("actualEarliestLogAt", plan.inventory().earliestLogAt());
            summary.put("actualLatestLogAt", plan.inventory().latestLogAt());
            summary.put("warningRecords", stats.warningRecords);
            summary.put("errorRecords", stats.errorRecords);
            summary.put("relevantRecords", stats.relevantRecords);
            summary.put("redactedValues", sanitizer.redactedCount());
            summary.put("warnings", warnings);
            summary.put("historyId", plan.history() == null ? null : plan.history().getId());
            summary.put("packageFiles", withFutureEntries(entries, "summary.json", "manifest.json"));
            writeJson(zip, entries, "summary.json", summary);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("createdAt", LocalDateTime.now());
            manifest.put("requestedRangeDays", plan.request().getDays());
            manifest.put("targetRangeStart", plan.effectiveStart());
            manifest.put("targetRangeEnd", plan.effectiveEnd());
            manifest.put("actualEarliestLogAt", plan.inventory().earliestLogAt());
            manifest.put("actualLatestLogAt", plan.inventory().latestLogAt());
            manifest.put("files", withFutureEntries(entries, "manifest.json"));
            manifest.put("warnings", warnings);
            writeJson(zip, entries, "manifest.json", manifest);
        }
    }

    public String filename(ExportPlan plan) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return plan.history() == null ? "biliupforjava-diagnostics-global-" + now + ".zip"
                : "biliupforjava-diagnostics-history-" + plan.history().getId() + "-" + now + ".zip";
    }

    private void writeRelevant(ZipOutputStream zip, List<String> entries, ExportPlan plan,
                               DiagnosticSecretSanitizer sanitizer, List<String> warnings, ExportStats stats,
                               DiagnosticExportProgressService.ProgressReporter progress) throws IOException {
        zip.putNextEntry(new ZipEntry("logs/relevant.log"));
        entries.add("logs/relevant.log");
        Set<String> identifiers = identifiers(plan.history(), plan.parts());
        RelevanceWriter writer = new RelevanceWriter(zip, sanitizer, plan.history() != null, identifiers,
                plan.request().getOccurredAt(), stats);
        for (LogArchiveService.LogFile file : plan.relevantSource()) {
            try (DiagnosticExportProgressService.FileProgress fileProgress = progress.file(file.size(), file.path().getFileName().toString())) {
                streamRecords(file, writer::accept, fileProgress);
            } catch (IOException e) {
                warnings.add("无法读取日志文件 " + file.path().getFileName() + ": " + e.getMessage());
            }
        }
        writer.finish();
        zip.closeEntry();
    }

    private void writeFullWindow(ZipOutputStream zip, List<String> entries, List<LogArchiveService.LogFile> files,
                                 DiagnosticSecretSanitizer sanitizer, List<String> warnings,
                                 DiagnosticExportProgressService.ProgressReporter progress) throws IOException {
        zip.putNextEntry(new ZipEntry("logs/full-window.log"));
        entries.add("logs/full-window.log");
        for (LogArchiveService.LogFile file : files) {
            try (DiagnosticExportProgressService.FileProgress fileProgress = progress.file(file.size(), file.path().getFileName().toString())) {
                archiveService.streamLines(List.of(file), line -> {
                    try {
                        zip.write((sanitizer.sanitizeText(line) + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new ZipWriteException(e);
                    }
                }, fileProgress);
            } catch (ZipWriteException e) {
                throw e.getCause();
            } catch (IOException e) {
                warnings.add("无法读取日志文件 " + file.path().getFileName() + ": " + e.getMessage());
            }
        }
        zip.closeEntry();
    }

    private void streamRecords(LogArchiveService.LogFile file, Consumer<LogRecord> consumer) throws IOException {
        streamRecords(file, consumer, null);
    }

    private void streamRecords(LogArchiveService.LogFile file, Consumer<LogRecord> consumer,
                               java.util.function.LongConsumer bytesConsumer) throws IOException {
        try (var reader = archiveService.reader(file, bytesConsumer)) {
            StringBuilder current = null;
            LocalDateTime timestamp = null;
            String level = "INFO";
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LOG_HEADER.matcher(line);
                if (matcher.matches()) {
                    if (current != null) consumer.accept(new LogRecord(timestamp, level, current.toString()));
                    current = new StringBuilder(line);
                    timestamp = LocalDateTime.parse(matcher.group(1), LOG_TIME);
                    level = matcher.group(2);
                } else if (current != null) {
                    current.append('\n').append(line);
                } else {
                    current = new StringBuilder(line);
                    timestamp = null;
                    level = "INFO";
                }
            }
            if (current != null) consumer.accept(new LogRecord(timestamp, level, current.toString()));
        }
    }

    private List<Map<String, Object>> systemConfigs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SystemConfig config : systemConfigRepository.findAll()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("configKey", config.getConfigKey());
            map.put("configValue", config.getConfigValue());
            map.put("description", config.getDescription());
            result.add(map);
        }
        return result;
    }

    private List<Map<String, Object>> accounts(ExportPlan plan) {
        Set<Long> ids = new LinkedHashSet<>();
        if (plan.history() == null) {
            for (BiliBiliUser user : userRepository.findAll()) ids.add(user.getId());
        } else {
            if (plan.history().getPublishUserId() != null) ids.add(plan.history().getPublishUserId());
            if (plan.room() != null && plan.room().getUploadUserId() != null) ids.add(plan.room().getUploadUserId());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Long id : ids) {
            userRepository.findById(id).ifPresent(user -> result.add(accountMap(user)));
        }
        return result;
    }

    private Map<String, Object> accountMap(BiliBiliUser user) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", user.getId());
        map.put("uid", user.getUid());
        map.put("uname", user.getUname());
        map.put("face", user.getFace());
        map.put("updateTime", user.getUpdateTime());
        map.put("login", user.isLogin());
        map.put("enable", user.isEnable());
        map.put("enableSc", user.isEnableSc());
        map.put("accessTokenConfigured", user.getAccessToken() != null && !user.getAccessToken().isBlank());
        map.put("refreshTokenConfigured", user.getRefreshToken() != null && !user.getRefreshToken().isBlank());
        map.put("cookieConfigured", user.getCookies() != null && !user.getCookies().isBlank());
        return map;
    }

    private Map<String, Object> notifications(ExportPlan plan) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> channels = new ArrayList<>();
        for (NotificationChannel channel : notificationChannelRepository.findAll()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", channel.getId());
            map.put("name", channel.getName());
            map.put("type", channel.getType());
            map.put("enabled", channel.isEnabled());
            map.put("configJson", channel.getConfigJson());
            map.put("secretConfigured", channel.getSecretJson() != null && !channel.getSecretJson().isBlank());
            map.put("createTime", channel.getCreateTime());
            map.put("updateTime", channel.getUpdateTime());
            channels.add(map);
        }
        List<Object> rules = new ArrayList<>();
        for (NotificationRule rule : notificationRuleRepository.findAll()) {
            if (plan.history() == null || rule.getRoomId() == null || rule.getRoomId().equals(plan.history().getRoomId())) {
                rules.add(entityMap(rule));
            }
        }
        result.put("channels", channels);
        result.put("rules", rules);
        return result;
    }

    private List<Object> rooms(ExportPlan plan) {
        List<Object> result = new ArrayList<>();
        if (plan.history() == null) {
            for (RecordRoom room : roomRepository.findAll()) result.add(entityMap(room));
        } else if (plan.room() != null) {
            result.add(entityMap(plan.room()));
        }
        return result;
    }

    private Map<String, Object> runtimeInfo() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("javaVendor", System.getProperty("java.vendor"));
        result.put("osName", System.getProperty("os.name"));
        result.put("osVersion", System.getProperty("os.version"));
        result.put("timezone", System.getProperty("user.timezone"));
        result.put("inContainer", ContainerUtils.isRunningInContainer());
        result.put("availableProcessors", runtime.availableProcessors());
        result.put("maxMemory", runtime.maxMemory());
        result.put("totalMemory", runtime.totalMemory());
        result.put("freeMemory", runtime.freeMemory());
        result.put("recordWorkPath", environment.getProperty("record.work-path"));
        result.put("loggingPath", environment.getProperty("logging.file.path"));
        return result;
    }

    private Set<String> knownSecrets() {
        Set<String> values = new HashSet<>();
        addSecret(values, environment.getProperty("record.password"));
        addSecret(values, environment.getProperty("record.wx-push-token"));
        addSecret(values, environment.getProperty("bili.app-secret"));
        for (BiliBiliUser user : userRepository.findAll()) {
            addSecret(values, user.getAccessToken());
            addSecret(values, user.getRefreshToken());
            addSecret(values, user.getCookies());
        }
        for (SystemConfig config : systemConfigRepository.findAll()) {
            if (DiagnosticSecretSanitizer.isSensitiveKey(config.getConfigKey())) addSecret(values, config.getConfigValue());
        }
        for (RecordRoom room : roomRepository.findAll()) {
            addSecret(values, room.getServerChanSendKey());
            addSecret(values, room.getWxuid());
        }
        for (NotificationChannel channel : notificationChannelRepository.findAll()) {
            addSecret(values, channel.getSecretJson());
            addJsonScalarSecrets(values, channel.getSecretJson());
        }
        return values;
    }

    private static void addSecret(Set<String> values, String value) {
        if (value != null && value.trim().length() >= 3) values.add(value.trim());
    }

    private void addJsonScalarSecrets(Set<String> values, String json) {
        if (json == null || json.isBlank()) return;
        try {
            collectTextValues(values, objectMapper.readTree(json));
        } catch (IOException ignored) {
            // 非 JSON 配置仍会以整体字符串参与替换
        }
    }

    private static void collectTextValues(Set<String> values, JsonNode node) {
        if (node == null) return;
        if (node.isTextual()) {
            addSecret(values, node.asText());
        } else if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> collectTextValues(values, child));
        }
    }

    private static List<String> withFutureEntries(List<String> entries, String... names) {
        List<String> result = new ArrayList<>(entries);
        for (String name : names) {
            if (!result.contains(name)) result.add(name);
        }
        return result;
    }

    private static boolean matches(RecordHistory history, String query) {
        if (query.isBlank()) return true;
        return contains(history.getTitle(), query) || contains(history.getBvId(), query)
                || contains(history.getRoomId(), query) || contains(history.getRoomName(), query)
                || String.valueOf(history.getId()).equals(query);
    }

    private static boolean contains(String value, String query) {
        return value != null && value.toLowerCase().contains(query);
    }

    private static Map<String, Object> historyOption(RecordHistory history) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", history.getId());
        map.put("title", history.getTitle());
        map.put("roomId", history.getRoomId());
        map.put("roomName", history.getRoomName());
        map.put("bvId", history.getBvId());
        map.put("startTime", history.getStartTime());
        map.put("endTime", history.getEndTime());
        map.put("publish", history.isPublish());
        map.put("code", history.getCode());
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entityMap(Object entity) {
        return objectMapper.convertValue(entity, LinkedHashMap.class);
    }

    private List<Map<String, Object>> entityMaps(Collection<?> entities) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entity : entities) result.add(entityMap(entity));
        return result;
    }

    private Object sanitize(Object value, DiagnosticSecretSanitizer sanitizer) {
        return sanitizer.sanitizeStructured(value, null);
    }

    private void writeJson(ZipOutputStream zip, List<String> entries, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        entries.add(name);
        zip.write(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
        zip.closeEntry();
    }

    private void writeText(ZipOutputStream zip, List<String> entries, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        entries.add(name);
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static Set<String> identifiers(RecordHistory history, List<RecordHistoryPart> parts) {
        Set<String> result = new LinkedHashSet<>();
        if (history == null) return result;
        addIdentifier(result, "historyId", history.getId());
        addIdentifier(result, "roomId", history.getRoomId());
        addIdentifier(result, "bvId", history.getBvId());
        addIdentifier(result, "avId", history.getAvId());
        addIdentifier(result, "eventId", history.getEventId());
        addIdentifier(result, "sessionId", history.getSessionId());
        addIdentifier(result, "filePath", history.getFilePath());
        for (RecordHistoryPart part : parts) {
            addIdentifier(result, "partId", part.getId());
            addIdentifier(result, "cid", part.getCid());
            addIdentifier(result, "eventId", part.getEventId());
            addIdentifier(result, "sessionId", part.getSessionId());
            addIdentifier(result, "filePath", part.getFilePath());
            addIdentifier(result, "fileName", part.getFileName());
        }
        return result;
    }

    private static void addIdentifier(Set<String> result, String key, Object value) {
        if (value == null || String.valueOf(value).isBlank()) return;
        String string = String.valueOf(value);
        result.add(key + "=" + string);
        result.add("\"" + key + "\":\"" + string + "\"");
        result.add("\"" + key + "\":" + string);
    }

    public record ExportPlan(DiagnosticExportRequest request, RecordHistory history, List<RecordHistoryPart> parts,
                             RecordRoom room, List<LogArchiveService.LogFile> relevantSource,
                             List<LogArchiveService.LogFile> fullWindow, LogArchiveService.Inventory inventory,
                             LocalDateTime effectiveStart, LocalDateTime effectiveEnd) {
    }

    private record LogRecord(LocalDateTime timestamp, String level, String text) {
    }

    private static final class ExportStats {
        private int warningRecords;
        private int errorRecords;
        private int relevantRecords;
    }

    private static final class IndexedRecord {
        private final long index;
        private final LogRecord record;

        private IndexedRecord(long index, LogRecord record) {
            this.index = index;
            this.record = record;
        }
    }

    private static final class RelevanceWriter {
        private final ZipOutputStream zip;
        private final DiagnosticSecretSanitizer sanitizer;
        private final boolean targetMode;
        private final Set<String> identifiers;
        private final LocalDateTime occurredAt;
        private final ExportStats stats;
        private final Deque<IndexedRecord> before = new ArrayDeque<>();
        private long index;
        private long lastWritten = -1;
        private int following;

        private RelevanceWriter(ZipOutputStream zip, DiagnosticSecretSanitizer sanitizer, boolean targetMode,
                                Set<String> identifiers, LocalDateTime occurredAt, ExportStats stats) {
            this.zip = zip;
            this.sanitizer = sanitizer;
            this.targetMode = targetMode;
            this.identifiers = identifiers;
            this.occurredAt = occurredAt;
            this.stats = stats;
        }

        private void accept(LogRecord record) {
            boolean warn = "WARN".equalsIgnoreCase(record.level());
            boolean error = "ERROR".equalsIgnoreCase(record.level());
            if (warn) stats.warningRecords++;
            if (error) stats.errorRecords++;
            boolean identifierMatch = targetMode && identifiers.stream().anyMatch(record.text()::contains);
            boolean occurrenceMatch = occurredAt != null && record.timestamp() != null
                    && !record.timestamp().isBefore(occurredAt.minusMinutes(15))
                    && !record.timestamp().isAfter(occurredAt.plusMinutes(15));
            boolean matched = identifierMatch || warn || error || occurrenceMatch;
            IndexedRecord current = new IndexedRecord(index++, record);
            if (matched) {
                for (IndexedRecord previous : before) write(previous);
                write(current);
                following = CONTEXT_RECORDS;
            } else if (following > 0) {
                write(current);
                following--;
            }
            before.addLast(current);
            if (before.size() > CONTEXT_RECORDS) before.removeFirst();
        }

        private void write(IndexedRecord item) {
            if (item.index <= lastWritten) return;
            try {
                zip.write((sanitizer.sanitizeText(item.record.text()) + "\n").getBytes(StandardCharsets.UTF_8));
                lastWritten = item.index;
                stats.relevantRecords++;
            } catch (IOException e) {
                throw new ZipWriteException(e);
            }
        }

        private void finish() {
            // 所有后续上下文在读取时已写出
        }
    }

    private static final class ZipWriteException extends RuntimeException {
        private ZipWriteException(IOException cause) {
            super(cause);
        }

        @Override
        public IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
