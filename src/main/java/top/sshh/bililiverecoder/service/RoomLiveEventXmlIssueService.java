package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RoomLiveEventParseState;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventParseStateRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventXmlIssueRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RoomLiveEventXmlIssueService {

    private final RoomLiveEventXmlIssueRepository issueRepository;
    private final RoomLiveEventParseStateRepository parseStateRepository;
    private final RecordHistoryPartRepository partRepository;
    private final RecordHistoryRepository historyRepository;
    private final PartFileLocationRepository locationRepository;
    private final StorageRootService storageRootService;

    public RoomLiveEventXmlIssueService(RoomLiveEventXmlIssueRepository issueRepository,
                                        RoomLiveEventParseStateRepository parseStateRepository,
                                        RecordHistoryPartRepository partRepository,
                                        RecordHistoryRepository historyRepository,
                                        PartFileLocationRepository locationRepository,
                                        StorageRootService storageRootService) {
        this.issueRepository = issueRepository;
        this.parseStateRepository = parseStateRepository;
        this.partRepository = partRepository;
        this.historyRepository = historyRepository;
        this.locationRepository = locationRepository;
        this.storageRootService = storageRootService;
    }

    public Optional<RoomLiveEventXmlIssue> find(Long partId) {
        return partId == null ? Optional.empty() : issueRepository.findById(partId);
    }

    public List<RoomLiveEventXmlIssue> findByHistoryId(Long historyId) {
        return historyId == null ? List.of() : issueRepository.findByHistoryId(historyId);
    }

    @Transactional
    public IssueUpdate record(RecordHistoryPart part,
                              RoomLiveEventXmlIssue.IssueType issueType,
                              Long storageRootId,
                              String xmlPath,
                              String errorMessage) {
        if (part == null || part.getId() == null || issueType == null) {
            return new IssueUpdate(null, false);
        }
        RoomLiveEventXmlIssue issue = issueRepository.findById(part.getId()).orElse(null);
        boolean changed = issue == null
                || issue.getIssueType() != issueType
                || !Objects.equals(issue.getStorageRootId(), storageRootId)
                || !Objects.equals(issue.getXmlPath(), xmlPath)
                || !Objects.equals(issue.getErrorMessage(), abbreviate(errorMessage));
        LocalDateTime now = LocalDateTime.now();
        if (issue == null) {
            issue = new RoomLiveEventXmlIssue();
            issue.setPartId(part.getId());
            issue.setFirstDetectedAt(now);
        }
        issue.setHistoryId(part.getHistoryId());
        issue.setRoomId(part.getRoomId());
        issue.setIssueType(issueType);
        issue.setStorageRootId(storageRootId);
        issue.setXmlPath(xmlPath);
        issue.setErrorMessage(abbreviate(errorMessage));
        issue.setLastCheckedAt(now);
        return new IssueUpdate(issueRepository.save(issue), changed);
    }

    @Transactional
    public void clear(Long partId) {
        if (partId != null) {
            issueRepository.deleteById(partId);
        }
    }

    @Transactional
    public void prepareRecheck(Long partId) {
        if (partId == null) return;
        issueRepository.findById(partId).ifPresent(issue -> {
            issue.setIgnoredAt(null);
            issueRepository.save(issue);
        });
    }

    @Transactional
    public RoomLiveEventXmlIssue ensureFromParseState(RecordHistoryPart part, RoomLiveEventParseState state) {
        if (part == null || part.getId() == null || state == null || state.isSuccess()) {
            return null;
        }
        RoomLiveEventXmlIssue existing = issueRepository.findById(part.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        Long offlineRootId = offlineRootId(part.getId());
        RoomLiveEventXmlIssue.IssueType type = offlineRootId != null
                ? RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE
                : legacyIssueType(state.getErrorMessage());
        return record(part, type, offlineRootId, state.getXmlPath(), state.getErrorMessage()).issue();
    }

    @Transactional
    public int migrateLegacyIssues() {
        int migrated = 0;
        for (RoomLiveEventParseState state : parseStateRepository.findBySuccessFalse()) {
            if (state.getPartId() == null || issueRepository.existsById(state.getPartId())) {
                continue;
            }
            RecordHistoryPart part = partRepository.findById(state.getPartId()).orElse(null);
            if (part == null) {
                continue;
            }
            ensureFromParseState(part, state);
            migrated++;
        }
        return migrated;
    }

    public Map<String, Object> summary() {
        List<RoomLiveEventXmlIssue> issues = issueRepository.findAllByOrderByLastCheckedAtDesc();
        Map<String, Object> result = new LinkedHashMap<>();
        long ignored = issues.stream().filter(this::isIgnored).count();
        long missing = countActive(issues, RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED);
        long invalid = countActive(issues, RoomLiveEventXmlIssue.IssueType.INVALID_XML);
        long readFailed = countActive(issues, RoomLiveEventXmlIssue.IssueType.READ_FAILED);
        long offline = countActive(issues, RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE);
        long unresolved = countActive(issues, RoomLiveEventXmlIssue.IssueType.PATH_UNRESOLVED);
        long internal = countActive(issues, RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR);
        result.put("attentionCount", issues.size() - ignored);
        result.put("missingCount", missing);
        result.put("parseFailedCount", invalid + readFailed + internal);
        result.put("storageOfflineCount", offline);
        result.put("pathUnresolvedCount", unresolved);
        result.put("ignoredCount", ignored);
        result.put("totalCount", issues.size());
        return result;
    }

    public Map<String, Object> list(String status,
                                    String type,
                                    String roomId,
                                    Long historyId,
                                    String keyword,
                                    int page,
                                    int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));
        List<RoomLiveEventXmlIssue> matches = filter(status, type, roomId, historyId, keyword);
        int from = Math.min(matches.size(), safePage * safeSize);
        int to = Math.min(matches.size(), from + safeSize);
        List<RoomLiveEventXmlIssue> pageItems = matches.subList(from, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("total", matches.size());
        result.put("items", toItems(pageItems));
        result.put("summary", summary());
        return result;
    }

    @Transactional
    public Map<String, Object> ignore(Map<String, Object> request) {
        request = request == null ? Map.of() : request;
        boolean filterSelection = isFilterSelection(request);
        List<RoomLiveEventXmlIssue> selected = selectForAction(request, true);
        int skippedOfflineCount = 0;
        if (filterSelection) {
            int beforeFilter = selected.size();
            selected = selected.stream()
                    .filter(issue -> issue.getIssueType() != RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE)
                    .toList();
            skippedOfflineCount = beforeFilter - selected.size();
        }
        LocalDateTime now = LocalDateTime.now();
        int affected = 0;
        for (RoomLiveEventXmlIssue issue : selected) {
            if (!isIgnored(issue)) {
                issue.setIgnoredAt(now);
                issueRepository.save(issue);
                affected++;
            }
        }
        return actionResult(affected, skippedOfflineCount);
    }

    @Transactional
    public Map<String, Object> resume(Map<String, Object> request) {
        request = request == null ? Map.of() : request;
        List<RoomLiveEventXmlIssue> selected = selectForAction(request, true);
        int affected = 0;
        for (RoomLiveEventXmlIssue issue : selected) {
            if (isIgnored(issue)) {
                issue.setIgnoredAt(null);
                issueRepository.save(issue);
                affected++;
            }
        }
        return actionResult(affected);
    }

    public List<Long> activePartIdsByRoot(Long rootId) {
        if (rootId == null) return List.of();
        return issueRepository.findByStorageRootIdAndIssueType(rootId, RoomLiveEventXmlIssue.IssueType.ROOT_OFFLINE)
                .stream()
                .filter(issue -> !isIgnored(issue))
                .map(RoomLiveEventXmlIssue::getPartId)
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional
    public void deleteByHistoryId(Long historyId) {
        if (historyId != null) {
            issueRepository.deleteByHistoryId(historyId);
        }
    }

    @Transactional
    public void deleteByPartIds(Collection<Long> partIds) {
        if (partIds != null && !partIds.isEmpty()) {
            issueRepository.deleteByPartIdIn(partIds);
        }
    }

    private List<RoomLiveEventXmlIssue> filter(String status,
                                                String type,
                                                String roomId,
                                                Long historyId,
                                                String keyword) {
        RoomLiveEventXmlIssue.IssueType issueType = parseIssueType(type);
        String normalizedStatus = StringUtils.defaultString(status, "PENDING").trim().toUpperCase(Locale.ROOT);
        String needle = StringUtils.trimToEmpty(keyword).toLowerCase(Locale.ROOT);
        return issueRepository.findAllByOrderByLastCheckedAtDesc().stream()
                .filter(issue -> matchesStatus(issue, normalizedStatus))
                .filter(issue -> issueType == null || issue.getIssueType() == issueType)
                .filter(issue -> StringUtils.isBlank(roomId) || Objects.equals(roomId, issue.getRoomId()))
                .filter(issue -> historyId == null || Objects.equals(historyId, issue.getHistoryId()))
                .filter(issue -> needle.isBlank() || matchesKeyword(issue, needle))
                .toList();
    }

    private boolean matchesStatus(RoomLiveEventXmlIssue issue, String status) {
        if ("ALL".equals(status)) return true;
        if ("IGNORED".equals(status)) return isIgnored(issue);
        if ("PENDING".equals(status)) return !isIgnored(issue);
        if ("PARSE_FAILED".equals(status)) {
            return !isIgnored(issue) && isParseFailure(issue);
        }
        return !isIgnored(issue)
                && issue.getIssueType() != null
                && issue.getIssueType().name().equals(status);
    }

    private boolean matchesKeyword(RoomLiveEventXmlIssue issue, String needle) {
        if (StringUtils.containsIgnoreCase(issue.getXmlPath(), needle)
                || StringUtils.containsIgnoreCase(issue.getErrorMessage(), needle)
                || String.valueOf(issue.getPartId()).contains(needle)
                || String.valueOf(issue.getHistoryId()).contains(needle)
                || StringUtils.containsIgnoreCase(issue.getRoomId(), needle)) {
            return true;
        }
        RecordHistoryPart part = issue.getPartId() == null ? null : partRepository.findById(issue.getPartId()).orElse(null);
        if (part != null && StringUtils.containsIgnoreCase(part.getTitle(), needle)) {
            return true;
        }
        RecordHistory history = issue.getHistoryId() == null ? null : historyRepository.findById(issue.getHistoryId()).orElse(null);
        return history != null && StringUtils.containsIgnoreCase(history.getTitle(), needle);
    }

    private List<Map<String, Object>> toItems(List<RoomLiveEventXmlIssue> issues) {
        if (issues.isEmpty()) return List.of();
        Set<Long> partIds = issues.stream().map(RoomLiveEventXmlIssue::getPartId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> historyIds = issues.stream().map(RoomLiveEventXmlIssue::getHistoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, RecordHistoryPart> parts = partRepository.findByIdIn(new ArrayList<>(partIds)).stream()
                .collect(Collectors.toMap(RecordHistoryPart::getId, value -> value));
        Map<Long, RecordHistory> histories = new HashMap<>();
        for (RecordHistory history : historyRepository.findAllById(historyIds)) {
            histories.put(history.getId(), history);
        }
        Map<Long, RoomLiveEventParseState> states = parseStateRepository.findByPartIdIn(partIds).stream()
                .collect(Collectors.toMap(RoomLiveEventParseState::getPartId, value -> value, (a, b) -> a));
        Map<Long, List<PartFileLocation>> locations = locationRepository.findByPartIdIn(partIds).stream()
                .collect(Collectors.groupingBy(PartFileLocation::getPartId));
        Map<Long, StorageRoot.RootStatus> rootStatuses = new HashMap<>();
        for (RoomLiveEventXmlIssue issue : issues) {
            if (issue.getStorageRootId() != null && !rootStatuses.containsKey(issue.getStorageRootId())) {
                rootStatuses.put(issue.getStorageRootId(), storageRootService.findById(issue.getStorageRootId())
                        .map(StorageRoot::getStatus).orElse(null));
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (RoomLiveEventXmlIssue issue : issues) {
            RecordHistoryPart part = parts.get(issue.getPartId());
            RecordHistory history = histories.get(issue.getHistoryId());
            RoomLiveEventParseState state = states.get(issue.getPartId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("partId", issue.getPartId());
            item.put("historyId", issue.getHistoryId());
            item.put("roomId", issue.getRoomId());
            item.put("historyTitle", history == null ? null : history.getTitle());
            item.put("partTitle", part == null ? null : part.getTitle());
            item.put("partOrder", part == null ? null : part.getPartOrder());
            item.put("page", part == null ? null : part.getPage());
            item.put("startTime", part == null ? null : part.getStartTime());
            item.put("xmlFileName", fileName(issue.getXmlPath()));
            item.put("xmlPath", issue.getXmlPath());
            item.put("issueType", issue.getIssueType() == null ? null : issue.getIssueType().name());
            item.put("errorMessage", issue.getErrorMessage());
            item.put("storageRootId", issue.getStorageRootId());
            item.put("rootStatus", rootStatuses.get(issue.getStorageRootId()));
            item.put("firstDetectedAt", issue.getFirstDetectedAt());
            item.put("lastCheckedAt", issue.getLastCheckedAt());
            item.put("ignoredAt", issue.getIgnoredAt());
            item.put("cachedStatsAvailable", state != null && state.isSuccess());
            item.put("videoStates", locations.getOrDefault(issue.getPartId(), List.of()).stream()
                    .map(location -> location.getState().name()).distinct().toList());
            item.put("suggestion", suggestion(issue));
            result.add(item);
        }
        return result;
    }

    private List<RoomLiveEventXmlIssue> selectForAction(Map<String, Object> request, boolean allowFilter) {
        String mode = StringUtils.defaultString(asString(request.get("selectionMode")), "IDS").toUpperCase(Locale.ROOT);
        if ("FILTER".equals(mode)) {
            if (!allowFilter || !Boolean.TRUE.equals(request.get("confirmAll"))) {
                throw new IllegalArgumentException("批量处理筛选结果需要确认");
            }
            String status = asString(request.get("status"));
            String type = asString(request.get("type"));
            String roomId = asString(request.get("roomId"));
            Long historyId = asLong(request.get("historyId"));
            String keyword = asString(request.get("keyword"));
            return filter(status, type, roomId, historyId, keyword);
        }
        List<Long> partIds = asLongList(request.get("partIds"));
        if (partIds.isEmpty()) {
            throw new IllegalArgumentException("请选择需要处理的 XML 记录");
        }
        return issueRepository.findByPartIdIn(partIds);
    }

    private Map<String, Object> actionResult(int affected) {
        return actionResult(affected, 0);
    }

    private Map<String, Object> actionResult(int affected, int skippedOfflineCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("affectedCount", affected);
        result.put("skippedOfflineCount", skippedOfflineCount);
        result.put("summary", summary());
        return result;
    }

    private Long offlineRootId(Long partId) {
        if (partId == null) return null;
        return locationRepository.findByPartIdOrderByIdAsc(partId).stream()
                .map(PartFileLocation::getStorageRootId)
                .filter(Objects::nonNull)
                .filter(rootId -> storageRootService.findById(rootId)
                        .map(root -> root.getStatus() == StorageRoot.RootStatus.OFFLINE).orElse(false))
                .findFirst().orElse(null);
    }

    private RoomLiveEventXmlIssue.IssueType legacyIssueType(String error) {
        String normalized = StringUtils.defaultString(error).toLowerCase(Locale.ROOT);
        if (normalized.contains("xml not found")) {
            return RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED;
        }
        if (normalized.contains("access") || normalized.contains("denied") || normalized.contains("permission")) {
            return RoomLiveEventXmlIssue.IssueType.READ_FAILED;
        }
        if (normalized.contains("xml") || normalized.contains("line") || normalized.contains("document")) {
            return RoomLiveEventXmlIssue.IssueType.INVALID_XML;
        }
        return RoomLiveEventXmlIssue.IssueType.INTERNAL_ERROR;
    }

    private long countActive(List<RoomLiveEventXmlIssue> issues, RoomLiveEventXmlIssue.IssueType type) {
        return issues.stream().filter(issue -> !isIgnored(issue) && issue.getIssueType() == type).count();
    }

    private boolean isParseFailure(RoomLiveEventXmlIssue issue) {
        if (issue == null || issue.getIssueType() == null) return false;
        return switch (issue.getIssueType()) {
            case INVALID_XML, READ_FAILED, INTERNAL_ERROR -> true;
            default -> false;
        };
    }

    private boolean isIgnored(RoomLiveEventXmlIssue issue) {
        return issue != null && issue.getIgnoredAt() != null;
    }

    private RoomLiveEventXmlIssue.IssueType parseIssueType(String value) {
        if (StringUtils.isBlank(value)) return null;
        try {
            return RoomLiveEventXmlIssue.IssueType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String suggestion(RoomLiveEventXmlIssue issue) {
        if (issue == null || issue.getIssueType() == null) return "请重新检查 XML 文件";
        return switch (issue.getIssueType()) {
            case MISSING_UNEXPECTED -> "把 XML 放回原目录后重新检查，确定不恢复可停止检查";
            case INVALID_XML -> "使用 XML 简单修复工具处理后替换原文件，再重新检查";
            case READ_FAILED -> "检查文件权限或占用情况后重新检查";
            case ROOT_OFFLINE -> "恢复或重新映射存储目录后，系统会自动重新检查一次";
            case PATH_UNRESOLVED -> "检查存储目录映射和历史文件路径后重新检查";
            case INTERNAL_ERROR -> "查看错误详情并重新检查，持续出现请保留日志反馈";
        };
    }

    private static String fileName(String path) {
        if (StringUtils.isBlank(path)) return null;
        try {
            Path candidate = Path.of(path);
            return candidate.getFileName() == null ? path : candidate.getFileName().toString();
        } catch (Exception ignored) {
            int index = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            return index >= 0 ? path.substring(index + 1) : path;
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.length() <= 1000) return value;
        return value.substring(0, 1000);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isFilterSelection(Map<String, Object> request) {
        return "FILTER".equalsIgnoreCase(StringUtils.defaultString(asString(request.get("selectionMode")), "IDS"));
    }

    private static Long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<Long> asLongList(Object value) {
        if (!(value instanceof Collection<?> values)) return List.of();
        return values.stream().map(RoomLiveEventXmlIssueService::asLong)
                .filter(Objects::nonNull).distinct().toList();
    }

    public record IssueUpdate(RoomLiveEventXmlIssue issue, boolean changed) {
    }
}
