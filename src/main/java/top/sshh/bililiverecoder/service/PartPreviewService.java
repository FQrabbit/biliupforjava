package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PartPreviewService {

    private static final Pattern FFMPEG_TIME_PATTERN = Pattern.compile("time=(\\d{2}):(\\d{2}):(\\d{2}(?:\\.\\d+)?)");
    private static final List<String> ALLOWED_EXT = List.of(".flv", ".mp4", ".mkv", ".ts", ".mov", ".m4v", ".avi");

    @Value("${record.work-path}")
    private String workPath;
    @Value("${record.preview.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;
    @Value("${record.preview.cache-ttl-hours:24}")
    private long cacheTtlHours;
    @Value("${record.preview.max-concurrent-tasks:1}")
    private int maxConcurrentTasks;

    @Autowired
    private RecordHistoryPartRepository partRepository;

    private ExecutorService executor;
    private String resolvedFfmpegPath;
    private final Map<Long, PreviewTaskStatus> tasks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        workPath = normalizePath(workPath);
        resolvedFfmpegPath = resolveFfmpegPath();
        if (StringUtils.isBlank(resolvedFfmpegPath)) {
            log.warn("[BLR] {}", LogKvs.event("PartPreview.FFmpeg.NotFound")
                    .add("configuredPath", ffmpegPath)
                    .add("appDir", appDir())
                    .addIfNotBlank("processDir", currentProcessDir().map(Path::toString).orElse(null))
                    .add("workPath", workPath)
                    .add("installHint", "请将ffmpeg.exe放到exe同目录、程序启动目录、环境变量，或配置record.preview.ffmpeg-path"));
        } else {
            log.info("[BLR] {}", LogKvs.event("PartPreview.FFmpeg.Resolved")
                    .add("path", resolvedFfmpegPath));
        }
        executor = Executors.newFixedThreadPool(Math.max(1, maxConcurrentTasks), r -> {
            Thread t = new Thread(r, "part-preview-ffmpeg");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        for (PreviewTaskStatus task : tasks.values()) {
            cancelTask(task, false);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public Map<String, Object> meta(Long partId) {
        PreviewFile previewFile = resolvePreviewFile(partId);
        Map<String, Object> result = previewFile.toMap();
        if (!previewFile.available) {
            return result;
        }
        boolean cacheReady = isCacheReady(previewFile);
        if (cacheReady) {
            touchCache(previewFile);
        }
        result.put("cacheReady", cacheReady);
        result.put("sourceUrl", "/part/preview/" + partId + "/source");
        result.put("cacheUrl", cacheReady ? "/part/preview/" + partId + "/cache" : null);
        Path danmakuFile = resolveDanmakuFile(previewFile);
        result.put("danmakuReady", danmakuFile != null);
        result.put("danmakuUrl", danmakuFile == null ? null : "/part/preview/" + partId + "/danmaku");
        result.put("danmakuFileName", danmakuFile == null || danmakuFile.getFileName() == null ? null : danmakuFile.getFileName().toString());
        result.put("task", taskMap(tasks.get(partId)));
        return result;
    }

    public Map<String, Object> prepare(Long partId, boolean force) {
        PreviewFile previewFile = resolvePreviewFile(partId);
        if (!previewFile.available) {
            return Map.of("accepted", false, "message", previewFile.message, "task", taskMap(null));
        }
        PreviewTaskStatus existing = tasks.get(partId);
        if (existing != null && existing.isActive()) {
            return Map.of("accepted", true, "status", existing.status, "cacheReady", false, "task", taskMap(existing));
        }
        if (isCacheReady(previewFile)) {
            if (!force) {
                touchCache(previewFile);
                return Map.of("accepted", true, "status", "SUCCESS", "cacheReady", true, "task", taskMap(null));
            }
            deleteCache(previewFile);
        }
        PreviewTaskStatus status = new PreviewTaskStatus(partId, previewFile.durationSeconds);
        tasks.put(partId, status);
        executor.submit(() -> runPrepare(previewFile, status));
        return Map.of("accepted", true, "status", status.status, "cacheReady", false, "task", taskMap(status));
    }

    public Map<String, Object> task(Long partId) {
        PreviewTaskStatus status = tasks.get(partId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", taskMap(status));
        if (status == null || "SUCCESS".equals(status.status)) {
            PreviewFile previewFile = resolvePreviewFile(partId);
            boolean cacheReady = previewFile.available && isCacheReady(previewFile);
            result.put("cacheReady", cacheReady);
            result.put("cacheUrl", cacheReady ? "/part/preview/" + partId + "/cache" : null);
        } else {
            result.put("cacheReady", false);
            result.put("cacheUrl", null);
        }
        return result;
    }

    public Map<String, Object> cancel(Long partId) {
        PreviewTaskStatus status = tasks.get(partId);
        if (status == null || !status.isActive()) {
            return Map.of("cancelled", false, "task", taskMap(status));
        }
        cancelTask(status, true);
        return Map.of("cancelled", true, "task", taskMap(status));
    }

    public PreviewFile getSource(Long partId) {
        return resolvePreviewFile(partId);
    }

    public PreviewFile getCache(Long partId) {
        PreviewFile previewFile = resolvePreviewFile(partId);
        if (previewFile.available && isCacheReady(previewFile)) {
            touchCache(previewFile);
            return previewFile.withStreamFile(previewFile.cacheFile, "video/mp4");
        }
        return previewFile.unavailable("预览缓存不存在或已过期");
    }

    public PreviewFile getDanmaku(Long partId) {
        PreviewFile previewFile = resolvePreviewFile(partId);
        Path danmakuFile = resolveDanmakuFile(previewFile);
        if (previewFile.available && danmakuFile != null) {
            return previewFile.withStreamFile(danmakuFile, "application/xml; charset=utf-8");
        }
        return previewFile.unavailable("Danmaku file not found");
    }

    private void runPrepare(PreviewFile previewFile, PreviewTaskStatus status) {
        status.status = "RUNNING";
        status.message = "正在生成可拖动预览";
        status.startedAt = LocalDateTime.now();
        try {
            String ffmpeg = refreshFfmpegPathIfNeeded();
            if (StringUtils.isBlank(ffmpeg)) {
                status.status = "FAILED";
                status.percent = 0;
                status.message = "未找到 ffmpeg，请将 ffmpeg.exe 放到 exe 同目录、程序启动目录、程序目录/bin、程序目录/tools/ffmpeg/bin，或配置 record.preview.ffmpeg-path";
                status.finishedAt = LocalDateTime.now();
                return;
            }
            Files.createDirectories(previewFile.cacheDir);
            Files.deleteIfExists(previewFile.tempCacheFile);
            SourceSnapshot sourceBefore = snapshot(previewFile.sourceFile);
            List<String> command = List.of(
                    ffmpeg,
                    "-y",
                    "-fflags", "+genpts",
                    "-i", previewFile.sourceFile.toString(),
                    "-c", "copy",
                    "-movflags", "+faststart",
                    previewFile.tempCacheFile.toString()
            );
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            status.process = process;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    updateProgress(status, line);
                }
            }
            int code = process.waitFor();
            status.process = null;
            if ("CANCELLED".equals(status.status)) {
                Files.deleteIfExists(previewFile.tempCacheFile);
                return;
            }
            if (code != 0 || !Files.exists(previewFile.tempCacheFile) || Files.size(previewFile.tempCacheFile) <= 0) {
                status.status = "FAILED";
                status.percent = 0;
                status.message = "快速封装失败，已回退 FLV 预览";
                Files.deleteIfExists(previewFile.tempCacheFile);
                return;
            }
            SourceSnapshot sourceAfter = snapshot(previewFile.sourceFile);
            if (!sourceBefore.matches(sourceAfter)) {
                status.status = "FAILED";
                status.percent = 0;
                status.message = "源文件仍在写入，已放弃本次 MP4 缓存";
                Files.deleteIfExists(previewFile.tempCacheFile);
                return;
            }
            Files.move(previewFile.tempCacheFile, previewFile.cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            writeCacheMeta(previewFile, sourceAfter);
            touchCache(previewFile);
            status.status = "SUCCESS";
            status.percent = 100;
            status.message = "可拖动预览已生成";
            status.finishedAt = LocalDateTime.now();
        } catch (Exception e) {
            if (!"CANCELLED".equals(status.status)) {
                status.status = "FAILED";
                status.percent = 0;
                status.message = "快速封装失败: " + StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
                log.warn("[BLR] {}", LogKvs.event("PartPreview.PrepareFailed")
                        .add("partId", status.partId)
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }
            try {
                Files.deleteIfExists(previewFile.tempCacheFile);
            } catch (Exception ignored) {
            }
        } finally {
            status.process = null;
            if (status.finishedAt == null && !"RUNNING".equals(status.status)) {
                status.finishedAt = LocalDateTime.now();
            }
        }
    }

    private void updateProgress(PreviewTaskStatus status, String line) {
        if (line == null) {
            return;
        }
        Matcher matcher = FFMPEG_TIME_PATTERN.matcher(line);
        if (matcher.find() && status.durationSeconds > 0) {
            double seconds = parseSeconds(matcher.group(1), matcher.group(2), matcher.group(3));
            int percent = (int) Math.floor((seconds * 100.0d) / status.durationSeconds);
            status.percent = Math.max(1, Math.min(99, percent));
            status.message = "正在封装 " + status.percent + "%";
        }
    }

    private double parseSeconds(String hh, String mm, String ss) {
        try {
            return Integer.parseInt(hh) * 3600.0d + Integer.parseInt(mm) * 60.0d + Double.parseDouble(ss);
        } catch (Exception e) {
            return 0.0d;
        }
    }

    private void cancelTask(PreviewTaskStatus status, boolean deleteTemp) {
        status.status = "CANCELLED";
        status.percent = 0;
        status.message = "已取消封装，回退 FLV 预览";
        status.finishedAt = LocalDateTime.now();
        Process process = status.process;
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
                process.destroyForcibly();
            }
        }
        if (deleteTemp) {
            try {
                PreviewFile previewFile = resolvePreviewFile(status.partId);
                Files.deleteIfExists(previewFile.tempCacheFile);
            } catch (Exception ignored) {
            }
        }
    }

    private PreviewFile resolvePreviewFile(Long partId) {
        if (partId == null) {
            return PreviewFile.unavailableStatic("分P不存在");
        }
        Optional<RecordHistoryPart> partOptional = partRepository.findById(partId);
        if (partOptional.isEmpty()) {
            return PreviewFile.unavailableStatic("分P不存在");
        }
        RecordHistoryPart part = partOptional.get();
        if (StringUtils.isBlank(part.getFilePath())) {
            return PreviewFile.unavailableStatic("分P文件路径为空");
        }
        Path source = Paths.get(part.getFilePath()).normalize().toAbsolutePath();
        String lowerName = source.getFileName() == null ? "" : source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (ALLOWED_EXT.stream().noneMatch(lowerName::endsWith)) {
            return PreviewFile.unavailableStatic("不支持该文件格式");
        }
        if (!isUnderWorkPath(source)) {
            return PreviewFile.unavailableStatic("文件不在工作目录内");
        }
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            return PreviewFile.unavailableStatic("分P文件不存在");
        }
        Path cacheDir = Paths.get(workPath, "_preview_cache", String.valueOf(partId)).normalize().toAbsolutePath();
        String contentType = lowerName.endsWith(".mp4") || lowerName.endsWith(".m4v") ? "video/mp4" : "video/x-flv";
        return new PreviewFile(
                true,
                null,
                partId,
                part.getPage(),
                source,
                source,
                contentType,
                cacheDir,
                cacheDir.resolve("preview.mp4"),
                cacheDir.resolve("preview.tmp.mp4"),
                cacheDir.resolve("preview.meta.json"),
                part.getDuration(),
                part.isRecording()
        );
    }

    private boolean isUnderWorkPath(Path source) {
        try {
            Path work = Paths.get(workPath).normalize().toAbsolutePath();
            return source.normalize().toAbsolutePath().startsWith(work);
        } catch (Exception e) {
            return false;
        }
    }

    private Path resolveDanmakuFile(PreviewFile previewFile) {
        try {
            if (previewFile == null || !previewFile.available || previewFile.sourceFile == null) {
                return null;
            }
            Path source = previewFile.sourceFile.normalize().toAbsolutePath();
            Path parent = source.getParent();
            Path fileName = source.getFileName();
            if (parent == null || fileName == null) {
                return null;
            }
            String name = fileName.toString();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                return null;
            }
            Path danmaku = parent.resolve(name.substring(0, dot) + ".xml").normalize().toAbsolutePath();
            if (!isUnderWorkPath(danmaku) || !Files.exists(danmaku) || !Files.isRegularFile(danmaku)) {
                return null;
            }
            return danmaku;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isCacheReady(PreviewFile previewFile) {
        try {
            if (!Files.exists(previewFile.cacheFile)
                    || !Files.isRegularFile(previewFile.cacheFile)
                    || Files.size(previewFile.cacheFile) <= 0) {
                return false;
            }
            if (!Files.exists(previewFile.metaFile) || !Files.isRegularFile(previewFile.metaFile)) {
                return false;
            }
            return readCacheMeta(previewFile).matches(snapshot(previewFile.sourceFile));
        } catch (Exception e) {
            return false;
        }
    }

    private SourceSnapshot snapshot(Path sourceFile) throws IOException {
        return new SourceSnapshot(
                sourceFile.normalize().toAbsolutePath().toString(),
                Files.size(sourceFile),
                Files.getLastModifiedTime(sourceFile).toMillis()
        );
    }

    private SourceSnapshot readCacheMeta(PreviewFile previewFile) throws IOException {
        JSONObject json = JSON.parseObject(Files.readString(previewFile.metaFile, StandardCharsets.UTF_8));
        return new SourceSnapshot(
                json.getString("sourcePath"),
                json.getLongValue("sourceSize"),
                json.getLongValue("sourceLastModified")
        );
    }

    private void writeCacheMeta(PreviewFile previewFile, SourceSnapshot snapshot) throws IOException {
        JSONObject json = new JSONObject(true);
        json.put("sourcePath", snapshot.path);
        json.put("sourceSize", snapshot.size);
        json.put("sourceLastModified", snapshot.lastModified);
        json.put("generatedAt", System.currentTimeMillis());
        Files.writeString(previewFile.metaFile, json.toJSONString(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> taskMap(PreviewTaskStatus status) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (status == null) {
            map.put("status", "NONE");
            map.put("percent", 0);
            map.put("message", "");
            return map;
        }
        map.put("status", status.status);
        map.put("percent", status.percent);
        map.put("message", status.message);
        map.put("startedAt", status.startedAt);
        map.put("finishedAt", status.finishedAt);
        return map;
    }

    private void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
    }

    private void touchCache(PreviewFile previewFile) {
        touch(previewFile.cacheFile);
        touch(previewFile.metaFile);
    }

    private void deleteCache(PreviewFile previewFile) {
        try {
            Files.deleteIfExists(previewFile.tempCacheFile);
            Files.deleteIfExists(previewFile.cacheFile);
            Files.deleteIfExists(previewFile.metaFile);
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("PartPreview.CacheDeleteFailed")
                    .add("partId", previewFile.partId)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    @Scheduled(fixedDelayString = "${record.preview.cleanup-interval-ms:3600000}", initialDelay = 300000)
    public void cleanupExpiredPreviewCache() {
        Path root = Paths.get(workPath, "_preview_cache").normalize().toAbsolutePath();
        if (!Files.exists(root)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofHours(Math.max(1, cacheTtlHours)).toMillis();
        try {
            List<Path> paths = Files.walk(root)
                    .sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path path : paths) {
                if (path.equals(root)) {
                    continue;
                }
                try {
                    Long partId = parsePartId(root, path);
                    if (partId != null) {
                        PreviewTaskStatus status = tasks.get(partId);
                        if (status != null && status.isActive()) {
                            continue;
                        }
                    }
                    if (Files.isRegularFile(path) && Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        Files.deleteIfExists(path);
                    } else if (Files.isDirectory(path)) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("[BLR] {}", LogKvs.event("PartPreview.CacheCleanupFailed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
        }
    }

    private Long parsePartId(Path root, Path path) {
        try {
            Path rel = root.relativize(path);
            if (rel.getNameCount() <= 0) {
                return null;
            }
            return Long.parseLong(rel.getName(0).toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveFfmpegPath() {
        List<Path> candidates = new ArrayList<>();
        addConfiguredFfmpegCandidates(candidates, ffmpegPath);

        Path appDir = Paths.get(appDir()).toAbsolutePath().normalize();
        addBundledFfmpegCandidates(candidates, appDir);
        currentProcessDir().ifPresent(path -> addBundledFfmpegCandidates(candidates, path));

        if (StringUtils.isNotBlank(workPath)) {
            Path workDir = Paths.get(workPath).toAbsolutePath().normalize();
            addBundledFfmpegCandidates(candidates, workDir);
        }

        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }

        return findCommandOnPath(ffmpegExecutableName());
    }

    private synchronized String refreshFfmpegPathIfNeeded() {
        if (isExecutableFile(resolvedFfmpegPath)) {
            return resolvedFfmpegPath;
        }
        String refreshed = resolveFfmpegPath();
        if (!StringUtils.equals(resolvedFfmpegPath, refreshed)) {
            resolvedFfmpegPath = refreshed;
            if (StringUtils.isBlank(refreshed)) {
                log.warn("[BLR] {}", LogKvs.event("PartPreview.FFmpeg.NotFound")
                        .add("configuredPath", ffmpegPath)
                        .add("appDir", appDir())
                        .addIfNotBlank("processDir", currentProcessDir().map(Path::toString).orElse(null))
                        .add("workPath", workPath)
                        .add("installHint", "请将ffmpeg.exe放到exe同目录、程序启动目录、程序目录/bin、程序目录/tools/ffmpeg/bin，或配置record.preview.ffmpeg-path"));
            } else {
                log.info("[BLR] {}", LogKvs.event("PartPreview.FFmpeg.Resolved")
                        .add("path", refreshed));
            }
        }
        return resolvedFfmpegPath;
    }

    private boolean isExecutableFile(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        try {
            return Files.isRegularFile(Paths.get(path));
        } catch (Exception e) {
            return false;
        }
    }

    private void addConfiguredFfmpegCandidates(List<Path> candidates, String configuredPath) {
        if (StringUtils.isBlank(configuredPath) || "ffmpeg".equalsIgnoreCase(configuredPath.trim())) {
            return;
        }
        Path configured = Paths.get(configuredPath.trim());
        candidates.add(configured);
        candidates.add(configured.resolve(ffmpegExecutableName()));
        candidates.add(configured.resolve("bin").resolve(ffmpegExecutableName()));
    }

    private String appDir() {
        return Paths.get("").toAbsolutePath().normalize().toString();
    }

    private void addBundledFfmpegCandidates(List<Path> candidates, Path baseDir) {
        candidates.add(baseDir.resolve(ffmpegExecutableName()));
        candidates.add(baseDir.resolve("bin").resolve(ffmpegExecutableName()));
        candidates.add(baseDir.resolve("ffmpeg").resolve(ffmpegExecutableName()));
        candidates.add(baseDir.resolve("ffmpeg").resolve("bin").resolve(ffmpegExecutableName()));
        candidates.add(baseDir.resolve("tools").resolve("ffmpeg").resolve(ffmpegExecutableName()));
        candidates.add(baseDir.resolve("tools").resolve("ffmpeg").resolve("bin").resolve(ffmpegExecutableName()));
    }

    private Optional<Path> currentProcessDir() {
        try {
            Optional<String> command = ProcessHandle.current().info().command();
            if (command.isEmpty()) {
                return Optional.empty();
            }
            Path exe = Paths.get(command.get()).toAbsolutePath().normalize();
            return Optional.ofNullable(exe.getParent());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String findCommandOnPath(String executableName) {
        String pathEnv = System.getenv("PATH");
        if (StringUtils.isBlank(pathEnv)) {
            return null;
        }
        for (String entry : pathEnv.split(Pattern.quote(File.pathSeparator))) {
            if (StringUtils.isBlank(entry)) {
                continue;
            }
            Path candidate = Paths.get(entry).resolve(executableName);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    private String ffmpegExecutableName() {
        return isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    private boolean isWindows() {
        return StringUtils.containsIgnoreCase(System.getProperty("os.name"), "win");
    }

    private String normalizePath(String path) {
        return path == null ? "" : path.replace("\\", "/");
    }

    private record SourceSnapshot(String path, long size, long lastModified) {
        private boolean matches(SourceSnapshot other) {
            return other != null
                    && StringUtils.equals(path, other.path)
                    && size == other.size
                    && lastModified == other.lastModified;
        }
    }

    private static class PreviewTaskStatus {
        private final Long partId;
        private final double durationSeconds;
        private volatile String status = "PENDING";
        private volatile int percent = 0;
        private volatile String message = "等待封装";
        private volatile LocalDateTime startedAt;
        private volatile LocalDateTime finishedAt;
        private volatile Process process;

        private PreviewTaskStatus(Long partId, double durationSeconds) {
            this.partId = partId;
            this.durationSeconds = durationSeconds;
        }

        private boolean isActive() {
            return "PENDING".equals(status) || "RUNNING".equals(status);
        }
    }

    public record PreviewFile(boolean available,
                              String message,
                              Long partId,
                              int page,
                              Path sourceFile,
                              Path streamFile,
                              String contentType,
                              Path cacheDir,
                              Path cacheFile,
                              Path tempCacheFile,
                              Path metaFile,
                              double durationSeconds,
                              boolean recording) {

        private static PreviewFile unavailableStatic(String message) {
            return new PreviewFile(false, message, null, 0, null, null, null, null, null, null, null, 0, false);
        }

        private PreviewFile unavailable(String message) {
            return new PreviewFile(false, message, partId, page, sourceFile, streamFile, contentType, cacheDir, cacheFile, tempCacheFile, metaFile, durationSeconds, recording);
        }

        private PreviewFile withStreamFile(Path streamFile, String contentType) {
            return new PreviewFile(true, null, partId, page, sourceFile, streamFile, contentType, cacheDir, cacheFile, tempCacheFile, metaFile, durationSeconds, recording);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("available", available);
            map.put("message", message);
            map.put("partId", partId);
            map.put("page", page);
            map.put("fileName", sourceFile == null || sourceFile.getFileName() == null ? null : sourceFile.getFileName().toString());
            map.put("size", size(sourceFile));
            map.put("durationSeconds", durationSeconds);
            map.put("recording", recording);
            map.put("contentType", contentType);
            return map;
        }

        private long size(Path file) {
            try {
                return file == null ? 0L : Files.size(file);
            } catch (Exception e) {
                return 0L;
            }
        }
    }
}
