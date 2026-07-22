package top.sshh.bililiverecoder.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 日志文件的统一入口，所有读取都保持流式，避免大日志占满堆内存
 */
@Service
public class LogArchiveService {

    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    @Value("${logging.file.path:${record.work-path}/log/}")
    private String logPath;

    @Value("${logging.file.name:spring.log}")
    private String logName;

    @Value("${logging.logback.rollingpolicy.max-history:14}")
    private int maxHistory;

    @Value("${logging.logback.rollingpolicy.total-size-cap:512MB}")
    private String totalSizeCap;

    public List<LogFile> listFiles() {
        Path active = resolveActiveFile();
        Path directory = active.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }

        String activeName = active.getFileName().toString();
        Pattern archivePattern = Pattern.compile(Pattern.quote(activeName) + "\\.(\\d{4}-\\d{2}-\\d{2})\\.(\\d+)(?:\\.gz)?$");
        List<LogFile> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                Matcher matcher = archivePattern.matcher(path.getFileName().toString());
                if (!matcher.matches()) {
                    return;
                }
                try {
                    files.add(new LogFile(path, LocalDate.parse(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                            false, Files.size(path), -1));
                } catch (IOException | NumberFormatException ignored) {
                    // 文件在扫描时被删除或异常命名时直接跳过
                }
            });
        } catch (IOException ignored) {
            return List.of();
        }

        files.sort(Comparator.comparing(LogFile::date).thenComparingInt(LogFile::index).thenComparing(file -> file.path().getFileName().toString()));
        if (Files.isRegularFile(active)) {
            try {
                long snapshotLength = Files.size(active);
                LocalDate activeDate = Instant.ofEpochMilli(Files.getLastModifiedTime(active).toMillis()).atZone(ZONE_ID).toLocalDate();
                files.add(new LogFile(active, activeDate, Integer.MAX_VALUE, true, snapshotLength, snapshotLength));
            } catch (IOException ignored) {
                // 当前文件刚好滚动时，归档文件仍可继续使用
            }
        }
        return files;
    }

    public List<LogFile> filesForLastDays(int days) {
        LocalDate from = LocalDate.now(ZONE_ID).minusDays(Math.max(1, days) - 1L);
        return listFiles().stream().filter(file -> !file.date().isBefore(from)).toList();
    }

    public List<LogFile> filesForRange(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || to.isBefore(from)) return List.of();
        LocalDate firstDate = from.atZone(ZONE_ID).toLocalDate();
        LocalDate lastDate = to.atZone(ZONE_ID).toLocalDate();
        return listFiles().stream()
                .filter(file -> !file.date().isBefore(firstDate) && !file.date().isAfter(lastDate))
                .toList();
    }

    public Inventory inventory() {
        List<LogFile> files = listFiles();
        Map<String, Long> dailyBytes = new LinkedHashMap<>();
        for (LogFile file : files) {
            dailyBytes.merge(file.date().toString(), file.size(), Long::sum);
        }
        LocalDate earliest = files.isEmpty() ? null : files.get(0).date();
        LocalDate latest = files.isEmpty() ? null : files.get(files.size() - 1).date();
        List<String> gaps = new ArrayList<>();
        if (earliest != null && latest != null) {
            for (LocalDate date = earliest; date.isBefore(latest); date = date.plusDays(1)) {
                if (!dailyBytes.containsKey(date.toString())) {
                    gaps.add(date.toString());
                }
            }
        }
        long total = files.stream().mapToLong(LogFile::size).sum();
        return new Inventory(earliest == null ? null : earliest.toString(), latest == null ? null : latest.toString(),
                total, dailyBytes, gaps, maxHistory, totalSizeCap);
    }

    public List<String> tailLines(int maxLines) throws IOException {
        int safeMax = Math.max(1, maxLines);
        Deque<String> lines = new ArrayDeque<>(safeMax);
        for (LogFile file : listFiles()) {
            try (BufferedReader reader = reader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (lines.size() == safeMax) {
                        lines.removeFirst();
                    }
                    lines.addLast(line);
                }
            }
        }
        return new ArrayList<>(lines);
    }

    public List<String> contextLines(String keyword, int range) throws IOException {
        int safeRange = Math.max(0, range);
        Deque<String> before = new ArrayDeque<>(safeRange);
        List<String> latest = List.of();
        List<String> candidate = null;
        int remaining = 0;
        for (LogFile file : listFiles()) {
            try (BufferedReader reader = reader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (candidate != null && remaining > 0) {
                        candidate.add(line);
                        remaining--;
                        if (remaining == 0) {
                            latest = List.copyOf(candidate);
                        }
                    }
                    if (line.contains(keyword)) {
                        candidate = new ArrayList<>(before);
                        candidate.add(line);
                        remaining = safeRange;
                        if (remaining == 0) {
                            latest = List.copyOf(candidate);
                        }
                    }
                    if (safeRange > 0) {
                        if (before.size() == safeRange) {
                            before.removeFirst();
                        }
                        before.addLast(line);
                    }
                }
            }
        }
        if (candidate != null && remaining > 0) {
            latest = List.copyOf(candidate);
        }
        return latest;
    }

    public void streamLines(List<LogFile> files, Consumer<String> consumer) throws IOException {
        for (LogFile file : files) {
            try (BufferedReader reader = reader(file)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    consumer.accept(line);
                }
            }
        }
    }

    public BufferedReader reader(LogFile file) throws IOException {
        return new BufferedReader(new InputStreamReader(open(file), StandardCharsets.UTF_8), 65536);
    }

    public InputStream open(LogFile file) throws IOException {
        InputStream source = new FileInputStream(file.path().toFile());
        if (file.snapshotLength() >= 0) {
            source = new LimitedInputStream(source, file.snapshotLength());
        }
        return file.path().getFileName().toString().endsWith(".gz") ? new GZIPInputStream(source, 65536) : source;
    }

    private Path resolveActiveFile() {
        Path configured = Path.of(logName);
        if (configured.isAbsolute()) {
            return configured;
        }
        return Path.of(logPath).resolve(configured.getFileName().toString());
    }

    public record LogFile(Path path, LocalDate date, int index, boolean active, long size, long snapshotLength) {
    }

    public record Inventory(String earliestLogAt, String latestLogAt, long archiveSize, Map<String, Long> dailyBytes,
                            List<String> dateGaps, int maxHistory, String totalSizeCap) {
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int value = delegate.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int read = delegate.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
