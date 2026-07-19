package top.sshh.bililiverecoder.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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

    public enum WorkPathChangeMode { FUTURE_ONLY, RELOCATE_EXISTING }

    public record RootMatch(StorageRoot root, String relativePath, Path resolvedPath) {}
    public record WorkPathChange(boolean pending, String configuredPath, StorageRoot activeRoot) {}

    private final StorageRootRepository rootRepository;
    private final PartFileLocationRepository locationRepository;
    private final RecordHistoryPartRepository partRepository;
    private final String configuredWorkPath;
    private final String requestedChangeMode;
    private final String requestedChangeFrom;
    private final String requestedChangeTo;

    public StorageRootService(StorageRootRepository rootRepository,
                              PartFileLocationRepository locationRepository,
                              RecordHistoryPartRepository partRepository,
                              @Value("${record.work-path}") String workPath,
                              @Value("${record.work-path-change-mode:}") String requestedChangeMode,
                              @Value("${record.work-path-change-from:}") String requestedChangeFrom,
                              @Value("${record.work-path-change-to:}") String requestedChangeTo) {
        this.rootRepository = rootRepository;
        this.locationRepository = locationRepository;
        this.partRepository = partRepository;
        this.configuredWorkPath = workPath == null || workPath.isBlank()
                ? ""
                : normalizeAbsolute(workPath).toString();
        this.requestedChangeMode = requestedChangeMode;
        this.requestedChangeFrom = requestedChangeFrom;
        this.requestedChangeTo = requestedChangeTo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applyExplicitSetupChange() {
        if (!hasConfiguredWorkPath()) return;
        WorkPathChange change = workPathChange();
        if (!change.pending() || change.activeRoot() == null) return;
        if (requestedChangeMode == null || requestedChangeMode.isBlank()
                || requestedChangeFrom == null || requestedChangeFrom.isBlank()
                || requestedChangeTo == null || requestedChangeTo.isBlank()) return;
        if (!samePath(change.activeRoot().getPath(), requestedChangeFrom)
                || !samePath(configuredWorkPath, requestedChangeTo)) return;
        try {
            resolveWorkPathChange(WorkPathChangeMode.valueOf(requestedChangeMode));
            log.info("[BLR] {}", LogKvs.event("StorageRoot.WorkPathChange.Applied")
                    .add("mode", requestedChangeMode).add("from", requestedChangeFrom)
                    .add("to", requestedChangeTo));
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("StorageRoot.WorkPathChange.Failed")
                    .add("mode", requestedChangeMode).add("from", requestedChangeFrom)
                    .add("to", requestedChangeTo).add("err", e.getMessage()), e);
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
        requireConfiguredWorkPath();
        StorageRoot active = activeWorkRoot().orElseThrow(() -> new IllegalStateException("active work root missing"));
        if (!hasPendingWorkPathChange()) return active;
        if (mode == WorkPathChangeMode.FUTURE_ONLY) {
            active.setActiveForNewFiles(false);
            rootRepository.save(active);
            StorageRoot next = findByPath(configuredWorkPath, StorageRoot.RootType.WORK).orElseGet(StorageRoot::new);
            next.setRootType(StorageRoot.RootType.WORK);
            next.setPath(configuredWorkPath);
            next.setActiveForNewFiles(true);
            refreshHealth(next);
            return rootRepository.save(next);
        }
        validateRelocation(active, normalizeAbsolute(configuredWorkPath));
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
        if (!Files.isDirectory(targetRoot) || !Files.isReadable(targetRoot)) {
            throw new IllegalArgumentException("new work path is not readable");
        }
        List<PartFileLocation> sample = locationRepository
                .findTop20ByStorageRootIdAndStateOrderByIdAsc(
                        active.getId(), PartFileLocation.LocationState.AVAILABLE);
        for (PartFileLocation location : sample) {
            Path candidate = targetRoot.resolve(Paths.get(location.getRelativePath())).normalize();
            if (!isUnder(targetRoot, candidate) || !Files.isRegularFile(candidate)) {
                throw new IllegalArgumentException("relocation validation failed: " + location.getRelativePath());
            }
            try {
                if (location.getExpectedSize() > 0 && Files.size(candidate) != location.getExpectedSize()) {
                    throw new IllegalArgumentException("relocation size validation failed: " + location.getRelativePath());
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("relocation validation failed: " + location.getRelativePath(), e);
            }
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
