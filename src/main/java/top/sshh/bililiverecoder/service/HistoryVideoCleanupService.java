package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.*;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HistoryVideoCleanupService {
    private static final long PREVIEW_TTL_MS = 20 * 60_000L;
    private static final int MAX_PREVIEWS = 64;
    private static final Set<String> VIDEO_SUFFIXES = Set.of(
            "mp4", "flv", "mkv", "ts", "m4v", "mov", "webm", "avi", "m4s", "fmp4", "mpg", "mpeg", "m2ts");
    private static final Set<PartFileOperation.OperationStatus> ACTIVE = Set.of(
            PartFileOperation.OperationStatus.PENDING, PartFileOperation.OperationStatus.RUNNING);

    public record FileView(String id, List<Long> partIds, String scope, String path,
                           long size, boolean available, String reason) {}
    public record PartView(Long id, String label, String title, List<String> fileIds) {}
    public record Preview(String token, long expiresAt, String disabledReason,
                          List<PartView> parts, List<FileView> files) {}
    public record FileResult(String id, String status, String message, long deletedBytes) {}
    private record LocationSnapshot(Long id, Long partId, Long rootId, String relativePath, String rootPath) {}
    private record Identity(long size, long modified, long created, Object key) {}

    private static final class Target {
        final String id = UUID.randomUUID().toString();
        final Path path;
        final Path realPath;
        final Identity identity;
        final String reason;
        final List<LocationSnapshot> locations = new ArrayList<>();
        boolean archive;
        Target(Path path, Path realPath, Identity identity, String reason) {
            this.path = path; this.realPath = realPath; this.identity = identity; this.reason = reason;
        }
        List<Long> partIds() { return locations.stream().map(LocationSnapshot::partId).distinct().toList(); }
    }

    private static final class Draft {
        final Long historyId;
        final long expiresAt = System.currentTimeMillis() + PREVIEW_TTL_MS;
        final Map<String, Target> targets = new LinkedHashMap<>();
        final Map<String, FileResult> results = new HashMap<>();
        Draft(Long historyId) { this.historyId = historyId; }
    }

    private final RecordHistoryRepository histories;
    private final RecordHistoryPartRepository parts;
    private final PartFileLocationRepository locations;
    private final PartFileOperationRepository operations;
    private final PartFileLocationService locationService;
    private final StorageRootService roots;
    private final PartFileStorageAdapter storage;
    private final PartActivityCoordinator activities;
    private final PublishTaskService publishTasks;
    private final UploadProgressTracker uploadProgress;
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();

    public HistoryVideoCleanupService(RecordHistoryRepository histories, RecordHistoryPartRepository parts,
                                      PartFileLocationRepository locations, PartFileOperationRepository operations,
                                      PartFileLocationService locationService, StorageRootService roots,
                                      PartFileStorageAdapter storage, PartActivityCoordinator activities,
                                      PublishTaskService publishTasks, UploadProgressTracker uploadProgress) {
        this.histories = histories; this.parts = parts; this.locations = locations; this.operations = operations;
        this.locationService = locationService; this.roots = roots; this.storage = storage;
        this.activities = activities; this.publishTasks = publishTasks; this.uploadProgress = uploadProgress;
    }

    public Preview preview(Long historyId) {
        RecordHistory history = requireHistory(historyId);
        List<RecordHistoryPart> historyParts = parts.findByHistoryId(historyId);
        String blocked = disabledReason(history, historyParts);
        Draft draft = new Draft(historyId);
        Map<Path, Target> byPath = new LinkedHashMap<>();
        for (RecordHistoryPart part : historyParts) {
            locationService.ensureLegacyLocation(part);
            for (PartFileLocation location : locationService.findLocations(part.getId())) {
                if (location.getState() == PartFileLocation.LocationState.DELETED_BY_POLICY
                        || location.getState() == PartFileLocation.LocationState.MOVED_AWAY) continue;
                StorageRoot root = roots.findById(location.getStorageRootId()).orElse(null);
                Path path = null;
                Path real = null;
                Identity identity = null;
                String reason = null;
                try {
                    if (root == null) throw new IllegalArgumentException("文件未映射到受管理的存储目录");
                    path = roots.resolve(root, location.getRelativePath());
                    if (!videoPath(path)) throw new IllegalArgumentException("不是可清理的视频文件");
                    if (!roots.ensureOnline(root)) reason = "存储目录离线，本次跳过";
                    else if (location.getState() != PartFileLocation.LocationState.AVAILABLE) reason = "文件位置状态异常，请先检查文件";
                    else {
                        identity = identity(path);
                        real = path.toRealPath();
                        reason = sharedReferenceReason(real, historyId);
                    }
                } catch (Exception e) { reason = readableError(e); }
                if (path == null) {
                    try { path = Path.of(Objects.requireNonNullElse(location.getAbsolutePathSnapshot(), location.getRelativePath())); }
                    catch (Exception e) { path = Path.of("unknown-" + location.getId()); }
                }
                Path key = real == null ? path.toAbsolutePath().normalize() : real;
                Target target = byPath.get(key);
                if (target == null) {
                    target = new Target(path, real, identity, reason);
                    byPath.put(key, target);
                }
                target.archive |= root == null || root.getRootType() == StorageRoot.RootType.ARCHIVE;
                target.locations.add(new LocationSnapshot(location.getId(), part.getId(), location.getStorageRootId(),
                        location.getRelativePath(), root == null ? null : root.getPath()));
            }
        }
        if (byPath.size() > 2000) throw new IllegalArgumentException("视频数量超过单次清理上限，请先整理文件位置");
        byPath.values().forEach(target -> draft.targets.put(target.id, target));
        String token = UUID.randomUUID().toString();
        synchronized (drafts) {
            drafts.entrySet().removeIf(entry -> entry.getValue().expiresAt < System.currentTimeMillis());
            if (drafts.size() >= MAX_PREVIEWS) throw new IllegalStateException("清理预览较多，请稍后重试");
            drafts.put(token, draft);
        }
        List<PartView> views = new ArrayList<>();
        for (int i = 0; i < historyParts.size(); i++) {
            RecordHistoryPart part = historyParts.get(i);
            int order = part.getPartOrder() != null && part.getPartOrder() > 0
                    ? part.getPartOrder() : part.getPage() > 0 ? part.getPage() : i + 1;
            views.add(new PartView(part.getId(), "P" + order, part.getTitle(),
                    draft.targets.values().stream().filter(t -> t.partIds().contains(part.getId())).map(t -> t.id).toList()));
        }
        List<FileView> files = draft.targets.values().stream().map(target -> new FileView(
                target.id, target.partIds(), target.archive ? "ARCHIVE" : "WORK", target.path.toString(),
                target.identity == null ? 0 : target.identity.size(), target.reason == null && target.identity != null,
                target.reason)).toList();
        return new Preview(token, draft.expiresAt, blocked, views, files);
    }

    // 每次只执行确认清单中的一个文件，离开页面或请求失败不会继续删下一项
    public FileResult delete(Long historyId, String token, String fileId) {
        Draft draft = token == null ? null : drafts.get(token);
        if (draft == null || !Objects.equals(historyId, draft.historyId)) {
            throw new IllegalArgumentException("清理预览已失效，请重新选择视频");
        }
        synchronized (draft) {
            FileResult previous = draft.results.get(fileId);
            if (previous != null) return previous;
            if (draft.expiresAt < System.currentTimeMillis()) throw new IllegalArgumentException("清理预览已过期，请重新选择视频");
            Target target = draft.targets.get(fileId);
            if (target == null) throw new IllegalArgumentException("文件不在本次确认清单中");
            FileResult result = target.reason != null
                    ? new FileResult(fileId, "SKIPPED", target.reason, 0) : deleteTarget(draft.historyId, target);
            draft.results.put(fileId, result);
            return result;
        }
    }

    public FileResult result(Long historyId, String token, String fileId) {
        Draft draft = token == null ? null : drafts.get(token);
        if (draft == null || !Objects.equals(historyId, draft.historyId)) return null;
        synchronized (draft) { return draft.results.get(fileId); }
    }

    private FileResult deleteTarget(Long historyId, Target target) {
        List<PartActivityCoordinator.OperationLease> leases = new ArrayList<>();
        List<PartFileOperation> intents = new ArrayList<>();
        boolean deleted = false;
        try (PartActivityCoordinator.HistoryLease historyLease = activities.tryBeginHistoryFileOperation(historyId)) {
            if (historyLease == null) return skipped(target, "稿件仍在上传、接收任务或执行清理，请稍后重新选择");
            for (Long partId : target.partIds().stream().sorted().toList()) {
                PartActivityCoordinator.OperationLease lease = activities.tryBeginFileOperation(partId);
                if (lease == null) return skipped(target, "分P仍在上传或执行文件操作");
                leases.add(lease);
            }
            String blocked = disabledReason(requireHistory(historyId), parts.findByHistoryId(historyId));
            if (blocked != null) return skipped(target, blocked);
            List<PartFileLocation> sources = new ArrayList<>();
            for (LocationSnapshot snapshot : target.locations) {
                RecordHistoryPart part = parts.findById(snapshot.partId()).orElse(null);
                if (part == null || !Objects.equals(part.getHistoryId(), historyId)) return skipped(target, "分P已变更，请重新选择");
                PartFileLocation source = locations.findById(snapshot.id()).orElse(null);
                StorageRoot root = roots.findById(snapshot.rootId()).orElse(null);
                if (source == null || root == null || source.getState() != PartFileLocation.LocationState.AVAILABLE
                        || !Objects.equals(source.getPartId(), snapshot.partId())
                        || !Objects.equals(source.getStorageRootId(), snapshot.rootId())
                        || !Objects.equals(source.getRelativePath(), snapshot.relativePath())
                        || !Objects.equals(root.getPath(), snapshot.rootPath())) return skipped(target, "文件位置已变更，请重新选择");
                if (!roots.ensureOnline(root)) return skipped(target, "存储目录离线，本次跳过");
                Path current = roots.resolve(root, source.getRelativePath());
                if (!Objects.equals(current.toRealPath(), target.realPath)
                        || !Objects.equals(identity(current), target.identity)) return skipped(target, "视频文件已发生变化，请重新选择");
                sources.add(source);
            }
            String shared = sharedReferenceReason(target.realPath, historyId);
            if (shared != null) return skipped(target, shared);
            for (PartFileLocation source : sources) {
                PartFileOperation operation = new PartFileOperation();
                operation.setPartId(source.getPartId());
                operation.setSourceLocationId(source.getId());
                operation.setSourcePathSnapshot(roots.resolve(
                        roots.findById(source.getStorageRootId()).orElseThrow(), source.getRelativePath()).toString());
                operation.setTargetRootId(source.getStorageRootId());
                operation.setTargetRelativePath(source.getRelativePath());
                operation.setOperationType(PartFileOperation.OperationType.DELETE);
                operation.setOperationSource(PartFileOperation.OperationSource.MANUAL_CLEANUP);
                operation.setStatus(PartFileOperation.OperationStatus.RUNNING);
                operation.setStartedAt(LocalDateTime.now());
                operation.setAttemptCount(1);
                intents.add(operations.save(operation));
            }
            if (!Objects.equals(target.path.toRealPath(), target.realPath)
                    || !Objects.equals(identity(target.path), target.identity)) {
                finishIntents(intents, PartFileOperation.OperationStatus.FAILED, "执行前视频发生变化，请重新确认");
                return skipped(target, "执行前视频发生变化，请重新确认");
            }
            storage.delete(target.path);
            if (Files.exists(target.path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("视频文件删除后仍然存在");
            deleted = true;
            for (PartFileLocation source : sources) locationService.completeManualDelete(source.getPartId(), source);
            finishIntents(intents, PartFileOperation.OperationStatus.SUCCEEDED, null);
            return new FileResult(target.id, "DELETED", "已删除", target.identity.size());
        } catch (Exception e) {
            if (!deleted) finishIntents(intents, PartFileOperation.OperationStatus.FAILED, readableError(e));
            return new FileResult(target.id, deleted ? "DELETED" : "FAILED",
                    deleted ? "视频已删除，状态同步未完成，请刷新检查" : readableError(e), deleted ? target.identity.size() : 0);
        } finally {
            for (int i = leases.size() - 1; i >= 0; i--) leases.get(i).close();
        }
    }

    private void finishIntents(List<PartFileOperation> intents, PartFileOperation.OperationStatus status, String error) {
        for (PartFileOperation operation : intents) {
            operation.setStatus(status);
            operation.setErrorMessage(error);
            operation.setFinishedAt(LocalDateTime.now());
            operations.save(operation);
        }
    }

    private FileResult skipped(Target target, String reason) { return new FileResult(target.id, "SKIPPED", reason, 0); }

    private RecordHistory requireHistory(Long historyId) {
        return histories.findById(historyId).orElseThrow(() -> new IllegalArgumentException("录制历史不存在"));
    }

    private String disabledReason(RecordHistory history, List<RecordHistoryPart> historyParts) {
        if (history.isDeletePending()) return "稿件正在等待或执行删除";
        if (roots.hasPendingWorkPathChange()) return "工作目录变更尚未确认，请先完成目录设置";
        if (history.isRecording() || history.isStreaming() || historyParts.stream().anyMatch(RecordHistoryPart::isRecording)) {
            return "录制尚未结束，暂不能清理视频";
        }
        if (history.isEditPartsUploading() || activities.hasPendingHistory(history.getId())
                || !publishTasks.getActiveForHistory(history.getId()).isEmpty()) return "稿件还有上传、投稿、转码或剪辑任务，请先结束任务";
        for (RecordHistoryPart part : historyParts) {
            UploadProgressTracker.Progress progress = uploadProgress.getByPartId(part.getId());
            if (progress != null && progress.isActive()) return "分P正在上传或等待重试，暂不能清理视频";
            if (operations.findFirstByPartIdAndStatusInOrderByCreatedAtDesc(part.getId(), ACTIVE).isPresent()) {
                return "分P正在执行或等待文件操作，请先结束操作";
            }
        }
        return null;
    }

    private String sharedReferenceReason(Path path, Long historyId) throws IOException {
        String name = path.getFileName().toString();
        for (PartFileLocation other : locations.findByRelativePathEndingWithIgnoreCase(name)) {
            if (other.getState() == PartFileLocation.LocationState.DELETED_BY_POLICY
                    || other.getState() == PartFileLocation.LocationState.MOVED_AWAY) continue;
            RecordHistoryPart part = parts.findById(other.getPartId()).orElse(null);
            if (part != null && Objects.equals(part.getHistoryId(), historyId)) continue;
            StorageRoot root = roots.findById(other.getStorageRootId()).orElse(null);
            if (root == null) continue;
            Path candidate = roots.resolve(root, other.getRelativePath());
            if (sameExistingPath(candidate, path)) return "该视频还被其他历史记录引用，本次跳过";
        }
        for (RecordHistoryPart other : parts.findByFilePathEndingWithIgnoreCase(name)) {
            if (Objects.equals(other.getHistoryId(), historyId) || other.getFilePath() == null) continue;
            if (sameExistingPath(Path.of(other.getFilePath()), path)) return "该视频还被其他历史记录引用，本次跳过";
        }
        return null;
    }

    private static boolean sameExistingPath(Path candidate, Path expected) throws IOException {
        return candidate.toAbsolutePath().normalize().equals(expected) || (Files.exists(candidate) && candidate.toRealPath().equals(expected));
    }

    private static Identity identity(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) throw new IOException("视频不存在或不是普通文件");
        return new Identity(attributes.size(), attributes.lastModifiedTime().toMillis(),
                attributes.creationTime().toMillis(), attributes.fileKey());
    }

    private static boolean videoPath(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && VIDEO_SUFFIXES.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static String readableError(Exception error) {
        String value = error.getMessage();
        if (error instanceof java.nio.file.AccessDeniedException) value = "文件被占用或没有删除权限";
        if (error instanceof java.nio.file.NoSuchFileException) value = "视频文件不存在，请重新检查文件";
        if (value == null || value.isBlank()) value = "文件处理失败：" + error.getClass().getSimpleName();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
