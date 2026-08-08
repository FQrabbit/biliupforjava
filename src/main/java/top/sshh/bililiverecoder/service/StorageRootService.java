package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.StorageRootRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class StorageRootService {

    public enum WorkPathChangeMode { FUTURE_ONLY, REMAP_EXISTING, RELOCATE_EXISTING }

    public record RootMatch(StorageRoot root, String relativePath, Path resolvedPath) {}
    public record WorkPathChange(boolean pending, String configuredPath, StorageRoot activeRoot) {}

    private final StorageRootRepository rootRepository;
    private final PartFileLocationRepository locationRepository;
    private final RecordHistoryPartRepository partRepository;
    private final String configuredWorkPath;
    private final String requestedChangeMode;
    private final String requestedChangeFrom;
    private final String requestedChangeTo;
    private final ApplicationEventPublisher eventPublisher;

    public StorageRootService(StorageRootRepository rootRepository,
                              PartFileLocationRepository locationRepository,
                              RecordHistoryPartRepository partRepository,
                              @Value("${record.work-path}") String workPath,
                              @Value("${record.work-path-change-mode:}") String requestedChangeMode,
                              @Value("${record.work-path-change-from:}") String requestedChangeFrom,
                              @Value("${record.work-path-change-to:}") String requestedChangeTo,
                              ApplicationEventPublisher eventPublisher) {
        this.rootRepository = rootRepository;
        this.locationRepository = locationRepository;
        this.partRepository = partRepository;
        this.configuredWorkPath = workPath == null || workPath.isBlank()
                ? ""
                : normalizeAbsolute(workPath).toString();
        this.requestedChangeMode = requestedChangeMode;
        this.requestedChangeFrom = requestedChangeFrom;
        this.requestedChangeTo = requestedChangeTo;
        this.eventPublisher = eventPublisher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applyExplicitSetupChange() {
        // 工作目录变更统一通过交互式确认流程处理
        // 旧配置里可能还留有 work-path-change-* 配置项，这里只保留作诊断
        // 启动时不会在没有用户确认和完整后台校验的情况下自动更新数据库映射
        if (requestedChangeMode != null && !requestedChangeMode.isBlank()) {
            log.info("[BLR] {}", LogKvs.event("StorageRoot.WorkPathChange.PendingReview")
                    .add("mode", requestedChangeMode)
                    .add("from", requestedChangeFrom)
                    .add("to", requestedChangeTo));
        }
    }

    @PostConstruct
    @Transactional
    public void initializeWorkRoot() {
        if (!hasConfiguredWorkPath()) {
            log.info("[BLR] {}", LogKvs.event("StorageRoot.WorkPath.NotConfigured"));
            return;
        }
        List<StorageRoot> active = rootRepository.findByRootTypeAndActiveForNewFilesIsTrue(StorageRoot.RootType.WORK);
        if (active.isEmpty()) {
            StorageRoot root = new StorageRoot();
            root.setRootType(StorageRoot.RootType.WORK);
            root.setPath(configuredWorkPath);
            root.setActiveForNewFiles(true);
            refreshHealth(root);
            rootRepository.save(root);
            return;
        }
        if (active.size() > 1) {
            active.sort(Comparator.comparing(StorageRoot::getId));
            for (int i = 1; i < active.size(); i++) {
                StorageRoot duplicate = active.get(i);
                duplicate.setActiveForNewFiles(false);
                rootRepository.save(duplicate);
            }
        }
        refreshHealth(active.get(0));
        rootRepository.save(active.get(0));
    }

    public String configuredWorkPath() {
        return configuredWorkPath;
    }

    public WorkPathChange workPathChange() {
        StorageRoot active = activeWorkRoot().orElse(null);
        boolean pending = hasConfiguredWorkPath()
                && active != null
                && !samePath(active.getPath(), configuredWorkPath);
        return new WorkPathChange(pending, configuredWorkPath, active);
    }

    public boolean hasPendingWorkPathChange() {
        return workPathChange().pending();
    }

    public Optional<StorageRoot> activeWorkRoot() {
        return rootRepository.findByRootTypeAndActiveForNewFilesIsTrue(StorageRoot.RootType.WORK)
                .stream().min(Comparator.comparing(StorageRoot::getId));
    }

    @Transactional
    public StorageRoot resolveWorkPathChange(WorkPathChangeMode mode) {
        return resolveWorkPathChange(mode, false);
    }

    /**
     * 使用已经完成的完整校验结果，不在 HTTP 请求里再次扫描同一个网络目录
     * 只有确认 changeId 仍然有效且校验成功后，调用方才能传入 {@code prevalidated=true}
     */
    @Transactional
    public StorageRoot resolveWorkPathChange(WorkPathChangeMode mode, boolean prevalidated) {
        requireConfiguredWorkPath();
        StorageRoot active = activeWorkRoot().orElseThrow(() -> new IllegalStateException("active work root missing"));
        if (!hasPendingWorkPathChange()) return active;
        if (mode == WorkPathChangeMode.FUTURE_ONLY) {
            validateTargetDirectory(normalizeAbsolute(configuredWorkPath));
            active.setActiveForNewFiles(false);
            rootRepository.save(active);
            StorageRoot next = findByPath(configuredWorkPath, StorageRoot.RootType.WORK).orElseGet(StorageRoot::new);
            next.setRootType(StorageRoot.RootType.WORK);
            next.setPath(configuredWorkPath);
            next.setActiveForNewFiles(true);
            refreshHealth(next);
            return rootRepository.save(next);
        }
        if (!prevalidated) validateRelocation(active, normalizeAbsolute(configuredWorkPath));
        active.setPath(configuredWorkPath);
        refreshHealth(active);
        StorageRoot relocated = rootRepository.save(active);
        syncPathCaches(relocated);
        return relocated;
    }

    @Transactional
    public StorageRoot getOrCreateArchiveRoot(String path) {
        Path normalized = normalizeAbsolute(path);
        Optional<StorageRoot> existing = findByPath(normalized.toString(), StorageRoot.RootType.ARCHIVE);
        if (existing.isPresent()) return existing.get();
        StorageRoot root = new StorageRoot();
        root.setRootType(StorageRoot.RootType.ARCHIVE);
        root.setPath(normalized.toString());
        root.setActiveForNewFiles(false);
        refreshHealth(root);
        return rootRepository.save(root);
    }

    public Optional<StorageRoot> findById(Long id) {
        return id == null ? Optional.empty() : rootRepository.findById(id);
    }

    public List<StorageRoot> findAll() {
        return rootRepository.findAllByOrderByIdAsc();
    }

    public Optional<RootMatch> matchTrustedRoot(Path path) {
        Path target = normalizeAbsolute(path.toString());
        return findAll().stream()
                .filter(r -> r.getStatus() != StorageRoot.RootStatus.RETIRED)
                .map(r -> match(r, target))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .max(Comparator.comparingInt(m -> normalizeAbsolute(m.root().getPath()).getNameCount()));
    }

    public Optional<RootMatch> matchTrustedExisting(Path path) {
        try {
            Path targetReal = path.toRealPath();
            return findAll().stream()
                    .filter(r -> r.getStatus() == StorageRoot.RootStatus.ONLINE)
                    .map(root -> {
                        try {
                            Path rootReal = normalizeAbsolute(root.getPath()).toRealPath();
                            if (!isUnder(rootReal, targetReal)) return Optional.<RootMatch>empty();
                            return Optional.of(new RootMatch(root,
                                    rootReal.relativize(targetReal).toString().replace('\\', '/'), targetReal));
                        } catch (Exception e) {
                            return Optional.<RootMatch>empty();
                        }
                    })
                    .filter(Optional::isPresent).map(Optional::get)
                    .max(Comparator.comparingInt(m -> normalizeAbsolute(m.root().getPath()).getNameCount()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Path resolve(StorageRoot root, String relativePath) {
        if (root == null) throw new IllegalArgumentException("storage root missing");
        Path relative = Paths.get(relativePath == null ? "" : relativePath).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IllegalArgumentException("invalid relative path");
        }
        Path base = normalizeAbsolute(root.getPath());
        Path resolved = base.resolve(relative).normalize();
        if (!isUnder(base, resolved)) throw new IllegalArgumentException("path escapes storage root");
        verifyExistingAncestors(base, resolved);
        return resolved;
    }

    public boolean isOnline(StorageRoot root) {
        return root != null && root.getStatus() == StorageRoot.RootStatus.ONLINE;
    }

    @Transactional
    public boolean ensureOnline(StorageRoot root) {
        if (root == null || root.getStatus() == StorageRoot.RootStatus.RETIRED
                || root.getLastCheckedAt() == null) return false;
        Path path = normalizeAbsolute(root.getPath());
        boolean accessible = Files.isDirectory(path) && Files.isReadable(path);
        if (accessible == isOnline(root)) return accessible;
        refreshHealth(root);
        rootRepository.save(root);
        return isOnline(root);
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    @Transactional
    public void refreshAllHealth() {
        for (StorageRoot root : findAll()) {
            if (root.getStatus() == StorageRoot.RootStatus.RETIRED) continue;
            if (root.getLastCheckedAt() == null) continue;
            StorageRoot.RootStatus before = root.getStatus();
            refreshHealth(root);
            rootRepository.save(root);
            if (before != root.getStatus()) {
                log.info("[BLR] {}", LogKvs.event("StorageRoot.HealthChanged")
                        .add("rootId", root.getId()).add("path", root.getPath())
                        .add("before", before).add("after", root.getStatus()));
                if (before == StorageRoot.RootStatus.OFFLINE && root.getStatus() == StorageRoot.RootStatus.ONLINE) {
                    eventPublisher.publishEvent(new StorageRootOnlineEvent(root.getId()));
                }
            }
        }
    }

    @Transactional
    public void markOnline(StorageRoot root) {
        if (root == null) return;
        refreshHealth(root);
        rootRepository.save(root);
    }

    @Transactional
    public StorageRoot remap(Long rootId, String path) {
        StorageRoot root = rootRepository.findById(rootId)
                .orElseThrow(() -> new IllegalArgumentException("storage root missing"));
        Path normalized = normalizeAbsolute(path);
        if (!Files.isDirectory(normalized) || !Files.isReadable(normalized)) {
            throw new IllegalArgumentException("storage root path is not readable");
        }
        root.setPath(normalized.toString());
        refreshHealth(root);
        StorageRoot remapped = rootRepository.save(root);
        syncPathCaches(remapped);
        return remapped;
    }

    public static Path normalizeAbsolute(String path) {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path is blank");
        return Paths.get(path).toAbsolutePath().normalize();
    }

    public static boolean samePath(String left, String right) {
        if (left == null || right == null) return false;
        Path a = normalizeAbsolute(left);
        Path b = normalizeAbsolute(right);
        if (isWindows()) return a.toString().equalsIgnoreCase(b.toString());
        return a.equals(b);
    }

    private Optional<StorageRoot> findByPath(String path, StorageRoot.RootType type) {
        return findAll().stream()
                .filter(r -> r.getRootType() == type && samePath(r.getPath(), path))
                .findFirst();
    }

    private boolean hasConfiguredWorkPath() {
        return !configuredWorkPath.isBlank();
    }

    private void requireConfiguredWorkPath() {
        if (!hasConfiguredWorkPath()) {
            throw new IllegalStateException("record.work-path is not configured");
        }
    }

    private Optional<RootMatch> match(StorageRoot root, Path target) {
        try {
            Path base = normalizeAbsolute(root.getPath());
            if (!isUnder(base, target)) return Optional.empty();
            String relative = base.relativize(target).toString().replace('\\', '/');
            return Optional.of(new RootMatch(root, relative, target));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isUnder(Path base, Path child) {
        if (!isWindows()) return child.startsWith(base);
        String baseValue = base.toString().toLowerCase(Locale.ROOT);
        String childValue = child.toString().toLowerCase(Locale.ROOT);
        return childValue.equals(baseValue) || childValue.startsWith(baseValue + java.io.File.separator.toLowerCase(Locale.ROOT));
    }

    private static boolean isWindows() {
        return java.io.File.separatorChar == '\\';
    }

    private static void verifyExistingAncestors(Path base, Path resolved) {
        if (!Files.exists(base)) return;
        try {
            Path realBase = base.toRealPath();
            Path cursor = resolved;
            while (cursor != null && !Files.exists(cursor)) cursor = cursor.getParent();
            if (cursor != null && !isUnder(realBase, cursor.toRealPath())) {
                throw new IllegalArgumentException("path escapes storage root through symbolic link");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("unable to validate storage path", e);
        }
    }

    private void refreshHealth(StorageRoot root) {
        Path path = normalizeAbsolute(root.getPath());
        root.setPath(path.toString());
        boolean online = Files.isDirectory(path) && Files.isReadable(path);
        root.setStatus(online ? StorageRoot.RootStatus.ONLINE : StorageRoot.RootStatus.OFFLINE);
        root.setWritable(online && Files.isWritable(path));
        root.setLastCheckedAt(LocalDateTime.now());
    }

    private void validateRelocation(StorageRoot active, Path targetRoot) {
        validateTargetDirectory(targetRoot);
        List<PartFileLocation> locations = locationRepository
                .findByStorageRootIdOrderByIdAsc(active.getId());
        for (PartFileLocation location : locations) {
            if (location.getState() != PartFileLocation.LocationState.AVAILABLE) continue;
            Path candidate = targetRoot.resolve(Paths.get(location.getRelativePath())).normalize();
            if (!isUnder(targetRoot, candidate) || !Files.isRegularFile(candidate)) {
                throw new IllegalArgumentException("relocation validation failed: " + location.getRelativePath());
            }
            try {
                if (Files.size(candidate) != location.getExpectedSize()) {
                    throw new IllegalArgumentException("relocation size validation failed: " + location.getRelativePath());
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("relocation validation failed: " + location.getRelativePath(), e);
            }
        }
    }

    private static void validateTargetDirectory(Path targetRoot) {
        if (!Files.isDirectory(targetRoot) || !Files.isReadable(targetRoot) || !Files.isWritable(targetRoot)) {
            throw new IllegalArgumentException("new work path must be a readable and writable directory");
        }
    }

    private void syncPathCaches(StorageRoot root) {
        for (PartFileLocation location : locationRepository.findByStorageRootIdOrderByIdAsc(root.getId())) {
            Path resolved = resolve(root, location.getRelativePath());
            location.setAbsolutePathSnapshot(resolved.toString());
            locationRepository.save(location);
            if (location.getRole() != PartFileLocation.LocationRole.PRIMARY) continue;
            partRepository.findById(location.getPartId()).ifPresent(part -> {
                part.setFilePath(resolved.toString().replace('\\', '/'));
                partRepository.save(part);
            });
        }
    }
}
