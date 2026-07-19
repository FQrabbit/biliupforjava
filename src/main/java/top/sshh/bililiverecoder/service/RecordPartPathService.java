package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把路径写法收一收，别让同一个文件因为斜杠或大小写不一样被当成两份
 */
@Service
public class RecordPartPathService {

    @Value("${record.work-path}")
    private String workPath;

    private Path workRoot;

    @PostConstruct
    public void init() {
        workRoot = Path.of(workPath).toAbsolutePath().normalize();
    }

    public String resolveWebhookPath(String incomingPath) {
        if (StringUtils.isBlank(incomingPath)) {
            return "";
        }
        Path candidate = Path.of(incomingPath.replace('\\', '/'));
        Path resolved = candidate.isAbsolute() ? candidate : workRoot.resolve(candidate);
        return displayPath(resolved);
    }

    public String identityKey(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        String key = displayPath(Path.of(path.replace('\\', '/')));
        return isWindows() ? key.toLowerCase(Locale.ROOT) : key;
    }

    public boolean sameFile(String left, String right) {
        String leftKey = identityKey(left);
        return !leftKey.isBlank() && leftKey.equals(identityKey(right));
    }

    public PartSelection selectPreferredParts(List<RecordHistoryPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return new PartSelection(List.of(), List.of());
        }
        Map<String, RecordHistoryPart> selected = new LinkedHashMap<>();
        List<RecordHistoryPart> suppressed = new ArrayList<>();
        for (RecordHistoryPart part : parts) {
            String key = identityKey(part == null ? null : part.getFilePath());
            if (key.isBlank()) {
                key = "part:" + (part == null ? System.identityHashCode(parts) : part.getId());
            }
            RecordHistoryPart current = selected.get(key);
            if (current == null) {
                selected.put(key, part);
            } else if (preferredComparator().compare(part, current) < 0) {
                selected.put(key, part);
                suppressed.add(current);
            } else {
                suppressed.add(part);
            }
        }
        return new PartSelection(new ArrayList<>(selected.values()), suppressed);
    }

    private Comparator<RecordHistoryPart> preferredComparator() {
        return Comparator.comparing(this::hasUploadedIdentity, Comparator.reverseOrder())
                .thenComparing(this::hasCompletedMetadata, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingLong(this::metadataScore).reversed())
                .thenComparingInt(this::partOrder)
                .thenComparing(RecordHistoryPart::getId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private boolean hasUploadedIdentity(RecordHistoryPart part) {
        return part != null && part.isUpload() && StringUtils.isNotBlank(part.getFileName())
                && part.getCid() != null && part.getCid() > 0;
    }

    private boolean hasCompletedMetadata(RecordHistoryPart part) {
        return part != null && !part.isRecording() && part.getEndTime() != null;
    }

    private long metadataScore(RecordHistoryPart part) {
        if (part == null) {
            return 0L;
        }
        long score = 0L;
        if (part.getFileSize() > 0) score += 2L;
        if (part.getDuration() > 0) score += 1L;
        return score;
    }

    private int partOrder(RecordHistoryPart part) {
        if (part == null || part.getPartOrder() == null || part.getPartOrder() <= 0) {
            return Integer.MAX_VALUE;
        }
        return part.getPartOrder();
    }

    private String displayPath(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public record PartSelection(List<RecordHistoryPart> selected, List<RecordHistoryPart> suppressed) {
    }
}
