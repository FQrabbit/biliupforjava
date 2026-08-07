package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.DocumentFactory;
import org.dom4j.Element;
import org.dom4j.ElementHandler;
import org.dom4j.ElementPath;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class RoomLiveEventParseService {

    public static final int PARSER_VERSION = 2;
    private static final int BATCH_SIZE = 500;
    private static final int PART_PARSE_LOCK_COUNT = 256;

    private final Object[] partParseLocks = createPartParseLocks();

    @Autowired
    private RecordHistoryRepository historyRepository;
    @Autowired
    private RecordHistoryPartRepository partRepository;
    @Autowired
    private RoomLiveEventRepository eventRepository;
    @Autowired
    private RoomLiveEventParseStateRepository parseStateRepository;
    @Autowired
    private RoomLiveDanmuUserStatsRepository danmuUserStatsRepository;
    @Autowired
    private RoomLiveGiftCatalogRepository giftCatalogRepository;
    @Autowired
    private RoomLiveGiftCatalogService giftCatalogService;
    @Lazy
    @Autowired
    private StatsAggregationService statsAggregationService;
    @Autowired
    private PartFileLocationService partFileLocationService;
    @Autowired
    private RoomLiveEventXmlIssueService xmlIssueService;

    public Map<String, Object> parseHistory(Long historyId, boolean force) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (historyId == null) {
            result.put("success", false);
            result.put("reason", "historyId is null");
            return result;
        }
        int parsed = 0;
        int skipped = 0;
        for (RecordHistoryPart part : partRepository.findByHistoryIdOrderByStartTimeAsc(historyId)) {
            ParseResult partResult = parsePart(part, force);
            if (partResult.parsed) {
                parsed++;
            } else {
                skipped++;
            }
        }
        statsAggregationService.refreshHistoryStatsAsync(historyId);
        result.put("success", true);
        result.put("parsed", parsed);
        result.put("skipped", skipped);
        return result;
    }

    public ParseResult parsePart(RecordHistoryPart part, boolean force) {
        return parsePart(part, force, true);
    }

    public ParseResult parsePartQuietly(RecordHistoryPart part, boolean force) {
        return parsePart(part, force, false);
    }

    private ParseResult parsePart(RecordHistoryPart part, boolean force, boolean logFailedCached) {
        if (part == null || part.getId() == null) {
            return ParseResult.skipped("invalid part");
        }
        return statsAggregationService.withStatsWriteLock(() -> {
            synchronized (partParseLocks[lockIndex(part.getId())]) {
                return parsePartLocked(part, force, logFailedCached);
            }
        });
    }

    private ParseResult parsePartLocked(RecordHistoryPart part, boolean force, boolean logFailedCached) {
        if (!partRepository.existsById(part.getId())) {
            return ParseResult.skipped("part deleted");
        }
        if (part.getHistoryId() != null && !historyRepository.existsById(part.getHistoryId())) {
            return ParseResult.skipped("history deleted");
        }
        if (!force && (part.isRecording() || part.getEndTime() == null)) {
            log.debug("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.SkipActive")
                    .add("roomId", part.getRoomId())
                    .add("historyId", part.getHistoryId())
                    .add("partId", part.getId())
                    .add("recording", part.isRecording())
                    .add("endTimeNull", part.getEndTime() == null));
            return ParseResult.skipped("part active");
        }
        RoomLiveEventParseState state = parseStateRepository.findByPartId(part.getId());
        if (state == null) {
            state = new RoomLiveEventParseState();
            state.setPartId(part.getId());
        }
        state.setHistoryId(part.getHistoryId());
        state.setRoomId(part.getRoomId());
        if (force) {
            xmlIssueService.prepareRecheck(part.getId());
        } else {
            RoomLiveEventXmlIssue issue = xmlIssueService.find(part.getId()).orElse(null);
            if (issue != null) {
            return ParseResult.skipped("xml issue cached:" + issue.getIssueType(), issue.getIssueType());
            }
            if (!state.isSuccess() && state.getParserVersion() >= PARSER_VERSION) {
                RoomLiveEventXmlIssue legacyIssue = xmlIssueService.ensureFromParseState(part, state);
                return ParseResult.skipped("xml issue cached:" + legacyIssue.getIssueType(), legacyIssue.getIssueType());
            }
        }

        PartFileLocationService.CompanionResolution resolution = partFileLocationService
                .resolveCompanionState(part.getId(), ".xml");
        if (!resolution.available()) {
            RoomLiveEventXmlIssue.IssueType issueType = issueTypeFor(resolution);
            String xmlPath = resolution.expectedPath() == null ? null : resolution.expectedPath().toString();
            RoomLiveEventXmlIssueService.IssueUpdate update = xmlIssueService.record(part, issueType,
                    resolution.storageRootId(), xmlPath, resolution.message());
            saveUnavailableState(state, xmlPath, resolution.message());
            if ((update.changed() || force) && logFailedCached) {
                log.warn("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.IssueDetected")
                        .add("roomId", part.getRoomId())
                        .add("historyId", part.getHistoryId())
                        .add("partId", part.getId())
                        .add("issueType", issueType)
                        .add("filePath", xmlPath)
                        .add("err", resolution.message()));
            }
            return ParseResult.skipped("xml issue:" + issueType, issueType);
        }

        File xmlFile = resolution.path().toFile();
        state.setXmlPath(xmlFile.getPath());

        long lastModified = xmlFile.lastModified();
        long size = xmlFile.length();
        boolean missingDanmuUserStats = state.isSuccess()
                && state.getDanmuCount() > 0
                && !danmuUserStatsRepository.existsByPartId(part.getId());
        if (!force && !missingDanmuUserStats
                && state.getParserVersion() >= PARSER_VERSION
                && state.getXmlLastModified() == lastModified
                && state.getXmlSize() == size) {
            if (state.isSuccess()) {
                return ParseResult.skipped("up to date");
            }
            if (logFailedCached) {
                log.debug("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.SkipFailedCached")
                        .add("roomId", part.getRoomId())
                        .add("historyId", part.getHistoryId())
                        .add("partId", part.getId())
                        .add("filePath", xmlFile.getPath())
                        .add("err", state.getErrorMessage()));
            }
            return ParseResult.skipped("parse failed cached");
        }

        Optional<RecordHistory> historyOptional = part.getHistoryId() == null
                ? Optional.empty()
                : historyRepository.findById(part.getHistoryId());
        RecordHistory history = historyOptional.orElse(null);
        LocalDateTime liveStart = history != null && history.getStartTime() != null ? history.getStartTime() : part.getStartTime();
        LocalDateTime now = LocalDateTime.now();
        EventCounter counter = new EventCounter();
        List<RoomLiveEvent> batch = new ArrayList<>(BATCH_SIZE);
        Map<DanmuUserKey, RoomLiveDanmuUserStats> danmuUsers = new LinkedHashMap<>();
        Map<Integer, RoomLiveEvent> giftCatalogCandidates = new HashMap<>();
        long parseStartNs = System.nanoTime();

        try (FileInputStream stream = new FileInputStream(xmlFile)) {
            giftCatalogService.syncRoomGiftCatalog(part.getRoomId(), false);
            eventRepository.deleteByPartId(part.getId());
            danmuUserStatsRepository.deleteByPartId(part.getId());
            SAXReader saxReader = new SAXReader(new DocumentFactory());
            try {
                saxReader.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", false);
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.DisableSecureFailed")
                        .add("partId", part.getId())
                        .add("filePath", xmlFile.getPath())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }

            saxReader.addHandler("/i/d", eventHandler(path -> {
                Element element = path.getCurrent();
                counter.danmu++;
                addDanmuUserStats(danmuUsers, part, liveStart, now, element);
            }));

            saxReader.addHandler("/i/gift", eventHandler(path -> {
                Element element = path.getCurrent();
                RoomLiveEvent event = baseEvent(RoomLiveEvent.TYPE_GIFT, part, liveStart, now, element);
                event.setSendTime(secondsToMs(attr(element, "ts")));
                event.setUid(parseLong(attr(element, "uid")));
                event.setGiftName(truncate(attr(element, "giftname"), 255));
                event.setGiftCount(defaultLong(parseLong(attr(element, "giftcount")), 1L));
                enrichGift(event, attr(element, "raw"));
                if (event.getGiftId() != null) {
                    giftCatalogCandidates.put(event.getGiftId(), event);
                }
                addEvent(batch, event, counter);
                counter.gift++;
            }));

            saxReader.addHandler("/i/sc", eventHandler(path -> {
                Element element = path.getCurrent();
                RoomLiveEvent event = baseEvent(RoomLiveEvent.TYPE_SC, part, liveStart, now, element);
                event.setSendTime(secondsToMs(attr(element, "ts")));
                event.setUid(parseLong(attr(element, "uid")));
                event.setScPrice(normalizeScPrice(part, attr(element, "price")));
                event.setScDisplaySeconds(parseInteger(attr(element, "time")));
                event.setContent(truncate(element.getText(), 500));
                addEvent(batch, event, counter);
                counter.sc++;
            }));

            saxReader.addHandler("/i/guard", eventHandler(path -> {
                Element element = path.getCurrent();
                RoomLiveEvent event = baseEvent(RoomLiveEvent.TYPE_GUARD, part, liveStart, now, element);
                event.setSendTime(secondsToMs(attr(element, "ts")));
                event.setUid(parseLong(attr(element, "uid")));
                event.setGuardLevel(parseInteger(attr(element, "level")));
                event.setGuardCount(defaultInteger(parseInteger(attr(element, "count")), 1));
                addEvent(batch, event, counter);
                counter.guard++;
            }));

            saxReader.read(stream);
            flush(batch);
            flushDanmuUsers(danmuUsers);
            for (RoomLiveEvent event : giftCatalogCandidates.values()) {
                upsertGiftCatalog(event);
            }

            state.setXmlLastModified(lastModified);
            state.setXmlSize(size);
            state.setEventCount(counter.total);
            state.setDanmuCount(counter.danmu);
            state.setGiftCount(counter.gift);
            state.setScCount(counter.sc);
            state.setGuardCount(counter.guard);
            state.setSuccess(true);
            state.setErrorMessage(null);
            state.setParsedAt(now);
            state.setParserVersion(PARSER_VERSION);
            parseStateRepository.save(state);
            xmlIssueService.clear(part.getId());

            log.debug("[BLR] {}", LogKvs.event("RoomLiveEvent.Parse.Saved")
                    .add("roomId", part.getRoomId())
                    .add("historyId", part.getHistoryId())
                    .add("partId", part.getId())
                    .add("count", counter.total)
                    .add("danmu", counter.danmu)
                    .add("gift", counter.gift)
                    .add("sc", counter.sc)
                    .add("guard", counter.guard)
                    .addStageCostMs("total", parseStartNs));
            return ParseResult.parsed(counter.total);
        } catch (Exception e) {
            RoomLiveEventXmlIssue.IssueType issueType = issueTypeFor(e);
            RoomLiveEventXmlIssueService.IssueUpdate update = xmlIssueService.record(part, issueType,
                    resolution.storageRootId(), xmlFile.getPath(), e.getMessage());
            if (!state.isSuccess()) {
                state.setXmlLastModified(lastModified);
                state.setXmlSize(size);
                state.setSuccess(false);
                state.setErrorMessage(truncate(e.getMessage(), 1000));
                state.setParsedAt(LocalDateTime.now());
                state.setParserVersion(PARSER_VERSION);
                parseStateRepository.save(state);
            }
            LogKvs failureLog = LogKvs.event(parseFailureEvent(issueType))
                    .add("roomId", part.getRoomId())
                    .add("historyId", part.getHistoryId())
                    .add("partId", part.getId())
                    .add("filePath", xmlFile.getPath())
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .add("issueType", issueType)
                    .addStageCostMs("total", parseStartNs);
            if (shouldLogFailureStackTrace(issueType)) {
                log.warn("[BLR] {}", failureLog, e);
            } else {
                log.warn("[BLR] {}", failureLog);
            }
            return ParseResult.skipped("xml issue:" + update.issue().getIssueType(), update.issue().getIssueType());
        }
    }

    private void saveUnavailableState(RoomLiveEventParseState state, String xmlPath, String error) {
        if (state.isSuccess()) {
            return;
        }
        state.setXmlPath(xmlPath);
        state.setXmlLastModified(0L);
        state.setXmlSize(0L);
        state.setSuccess(false);
        state.setErrorMessage(truncate(error, 1000));
        state.setParsedAt(LocalDateTime.now());
        state.setParserVersion(PARSER_VERSION);
        parseStateRepository.save(state);
    }

    private RoomLiveEventXmlIssue.IssueType issueTypeFor(PartFileLocationService.CompanionResolution resolution) {
        return switch (resolution.state()) {
            case ROOT_OFFLINE -> RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE;
            case MISSING_UNEXPECTED -> RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED;
            case PATH_UNRESOLVED -> RoomLiveEventXmlIssue.IssueType.PATH_UNRESOLVED;
            case AVAILABLE -> RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR;
        };
    }

    private RoomLiveEventXmlIssue.IssueType issueTypeFor(Exception error) {
        if (error instanceof org.dom4j.DocumentException) {
            return RoomLiveEventXmlIssue.IssueType.INVALID_XML;
        }
        if (error instanceof java.io.IOException) {
            return RoomLiveEventXmlIssue.IssueType.READ_FAILED;
        }
        return RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR;
    }

    static String parseFailureEvent(RoomLiveEventXmlIssue.IssueType issueType) {
        if (issueType == RoomLiveEventXmlIssue.IssueType.INVALID_XML) {
            return "RoomLiveEvent.Parse.InvalidXml";
        }
        if (issueType == RoomLiveEventXmlIssue.IssueType.READ_FAILED) {
            return "RoomLiveEvent.Parse.ReadFailed";
        }
        return "RoomLiveEvent.Parse.InternalError";
    }

    static boolean shouldLogFailureStackTrace(RoomLiveEventXmlIssue.IssueType issueType) {
        return issueType == RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR;
    }

    private static Object[] createPartParseLocks() {
        Object[] locks = new Object[PART_PARSE_LOCK_COUNT];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static int lockIndex(Long partId) {
        return Math.floorMod(partId.hashCode(), PART_PARSE_LOCK_COUNT);
    }

    public int backfillMatureHistories(LocalDateTime endBefore, int limit) {
        int parsed = 0;
        int checked = 0;
        for (RecordHistory history : historyRepository.findByEndTimeIsNotNullOrderByEndTimeDesc()) {
            if (history == null || history.getId() == null) {
                continue;
            }
            if (history.isRecording() || history.isStreaming() || history.getEndTime() == null || history.getEndTime().isAfter(endBefore)) {
                continue;
            }
            checked++;
            boolean historyParsed = false;
            for (RecordHistoryPart part : partRepository.findByHistoryIdOrderByStartTimeAsc(history.getId())) {
                ParseResult result = parsePart(part, false);
                historyParsed = historyParsed || result.parsed;
            }
            if (historyParsed) {
                parsed++;
                statsAggregationService.refreshHistoryStatsAsync(history.getId());
                if (parsed >= Math.max(1, limit)) {
                    break;
                }
            }
        }
        if (parsed > 0) {
            log.info("[BLR] {}", LogKvs.event("RoomLiveEvent.Backfill.Done")
                    .add("checked", checked)
                    .add("parsedHistories", parsed)
                    .add("endBefore", endBefore));
        }
        return parsed;
    }

    private ElementHandler eventHandler(java.util.function.Consumer<ElementPath> consumer) {
        return new ElementHandler() {
            @Override
            public void onStart(ElementPath path) {
            }

            @Override
            public void onEnd(ElementPath path) {
                try {
                    consumer.accept(path);
                } finally {
                    path.getCurrent().detach();
                }
            }
        };
    }

    private RoomLiveEvent baseEvent(String type, RecordHistoryPart part, LocalDateTime liveStart, LocalDateTime now, Element element) {
        RoomLiveEvent event = new RoomLiveEvent();
        event.setHistoryId(part.getHistoryId());
        event.setPartId(part.getId());
        event.setRoomId(part.getRoomId());
        event.setLiveDate(liveStart == null ? null : liveStart.toLocalDate());
        event.setType(type);
        event.setUname(truncate(attr(element, "user"), 255));
        event.setCreatedAt(now);
        return event;
    }

    private void addEvent(List<RoomLiveEvent> batch, RoomLiveEvent event, EventCounter counter) {
        if (event.getSendTime() != null && event.getSendTime() < 0) {
            return;
        }
        batch.add(event);
        counter.total++;
        if (batch.size() >= BATCH_SIZE) {
            flush(batch);
        }
    }

    private void flush(List<RoomLiveEvent> batch) {
        if (!batch.isEmpty()) {
            eventRepository.saveAll(batch);
            batch.clear();
        }
    }

    private void addDanmuUserStats(Map<DanmuUserKey, RoomLiveDanmuUserStats> danmuUsers,
                                   RecordHistoryPart part,
                                   LocalDateTime liveStart,
                                   LocalDateTime now,
                                   Element element) {
        Long uid = danmuUid(element);
        String uname = truncate(attr(element, "user"), 255);
        if (uid == null && StringUtils.isBlank(uname)) {
            return;
        }
        DanmuUserKey key = new DanmuUserKey(uid, uname == null ? "" : uname);
        RoomLiveDanmuUserStats stats = danmuUsers.computeIfAbsent(key, ignored -> {
            RoomLiveDanmuUserStats value = new RoomLiveDanmuUserStats();
            value.setHistoryId(part.getHistoryId());
            value.setPartId(part.getId());
            value.setRoomId(part.getRoomId());
            value.setLiveDate(liveStart == null ? null : liveStart.toLocalDate());
            value.setUid(uid);
            value.setUname(uname);
            value.setStatsUpdatedAt(now);
            value.setParserVersion(PARSER_VERSION);
            return value;
        });
        stats.setDanmuCount(stats.getDanmuCount() + 1);
    }

    private void flushDanmuUsers(Map<DanmuUserKey, RoomLiveDanmuUserStats> danmuUsers) {
        if (!danmuUsers.isEmpty()) {
            danmuUserStatsRepository.saveAll(danmuUsers.values());
            danmuUsers.clear();
        }
    }

    private Long danmuUid(Element element) {
        String p = attr(element, "p");
        if (StringUtils.isBlank(p)) {
            return null;
        }
        String[] values = p.split(",");
        if (values.length <= 6) {
            return null;
        }
        return parseLong(values[6]);
    }

    private void enrichGift(RoomLiveEvent event, String raw) {
        if (StringUtils.isBlank(raw)) {
            return;
        }
        try {
            JSONObject json = JSON.parseObject(raw);
            event.setGiftId(firstInteger(json, "gift_id", "giftId", "id"));
            event.setGiftPriceCoin(firstLong(json, "price", "discount_price"));
            event.setGiftTotalCoin(firstLong(json, "total_coin", "combo_total_coin"));
            event.setGiftCoinType(firstString(json, "coin_type"));
            if (event.getGiftTotalCoin() == null && event.getGiftPriceCoin() != null && event.getGiftCount() != null) {
                event.setGiftTotalCoin(event.getGiftPriceCoin() * event.getGiftCount());
            }
        } catch (Exception ignored) {
        }
    }

    private void upsertGiftCatalog(RoomLiveEvent event) {
        if (event.getGiftId() == null) {
            return;
        }
        RoomLiveGiftCatalog catalog = giftCatalogRepository.findByRoomIdAndGiftId(event.getRoomId(), event.getGiftId());
        if (catalog == null) {
            catalog = new RoomLiveGiftCatalog();
            catalog.setRoomId(event.getRoomId());
            catalog.setGiftId(event.getGiftId());
        }
        if (StringUtils.isNotBlank(event.getGiftName())) {
            catalog.setGiftName(event.getGiftName());
        }
        if (event.getGiftPriceCoin() != null) {
            catalog.setPriceCoin(event.getGiftPriceCoin());
            catalog.setPriceCny(giftCatalogService.toCny(event.getGiftPriceCoin()));
        }
        catalog.setUpdatedAt(LocalDateTime.now());
        giftCatalogRepository.save(catalog);
    }

    private String attr(Element element, String name) {
        return element.attribute(name) == null ? null : element.attribute(name).getValue();
    }

    private Long secondsToMs(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Math.round(Double.parseDouble(value) * 1000);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal normalizeScPrice(RecordHistoryPart part, String value) {
        if (StringUtils.isBlank(value)) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal price = new BigDecimal(value);
            if ("blrec".equals(part.getSourceType())) {
                price = price.divide(BigDecimal.valueOf(1000));
            }
            return price;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private Integer firstInteger(JSONObject json, String... keys) {
        Long value = firstLong(json, keys);
        return value == null ? null : value.intValue();
    }

    private Long firstLong(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null && StringUtils.isNumeric(value.toString())) {
                return Long.parseLong(value.toString());
            }
        }
        return null;
    }

    private String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }

    private Long parseLong(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer parseInteger(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Long defaultLong(Long value, Long fallback) {
        return value == null ? fallback : value;
    }

    private Integer defaultInteger(Integer value, Integer fallback) {
        return value == null ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static class EventCounter {
        private int total;
        private int danmu;
        private int gift;
        private int sc;
        private int guard;
    }

    private record DanmuUserKey(Long uid, String uname) {
    }

    public record ParseResult(boolean parsed, int count, String reason, RoomLiveEventXmlIssue.IssueType issueType) {
        private static ParseResult parsed(int count) {
            return new ParseResult(true, count, null, null);
        }

        private static ParseResult skipped(String reason) {
            return new ParseResult(false, 0, reason, null);
        }

        private static ParseResult skipped(String reason, RoomLiveEventXmlIssue.IssueType issueType) {
            return new ParseResult(false, 0, reason, issueType);
        }
    }
}
