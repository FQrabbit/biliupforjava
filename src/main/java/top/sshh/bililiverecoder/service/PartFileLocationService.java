package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PartFileLocationService {

    public enum LocalFileState {
        AVAILABLE_WORK, AVAILABLE_ARCHIVE, DELETED_BY_POLICY, ROOT_OFFLINE,
        MISSING_UNEXPECTED, PROCESSING, PROCESS_FAILED, UNKNOWN
    }

    public enum CompanionState {
        AVAILABLE, ROOT_OFFLINE, MISSING_UNEXPECTED, PATH_UNRESOLVED
    }

    public record FileResolution(LocalFileState state, Path path, PartFileLocation location,
                                 StorageRoot root, String message) {
        public boolean available() { return path != null && location != null; }
    }

    public enum RecoveryStatus { RECOVERED, ALREADY_AVAILABLE, MISSING, ROOT_OFFLINE, SKIPPED_POLICY, FAILED }
    public record RecoveryResult(RecoveryStatus status, Path path, String reason) {}

    @Transactional
    public RecoveryResult recoverExact(Long partId) {
        RecordHistoryPart part = partId == null ? null : partRepository.findById(partId).orElse(null);
        if (part == null) return new RecoveryResult(RecoveryStatus.FAILED, null, "part missing");
        if (part.isFileDelete()) return new RecoveryResult(RecoveryStatus.SKIPPED_POLICY, null, "fileDelete=true");
        List<PartFileLocation> locations = locationRepository.findByPartIdOrderByIdAsc(partId);
        boolean candidate = locations.isEmpty();
        for (PartFileLocation l : locations) {
            if (l.getStorageRootId() == null || l.getState() == PartFileLocation.LocationState.PROCESS_FAILED
                    || l.getState() == PartFileLocation.LocationState.MISSING_UNEXPECTED) candidate = true;
            if (l.getState() == PartFileLocation.LocationState.DELETED_BY_POLICY
                    || l.getState() == PartFileLocation.LocationState.MOVED_AWAY
                    || l.getState() == PartFileLocation.LocationState.PROCESSING) {
                if (!candidate) return new RecoveryResult(RecoveryStatus.SKIPPED_POLICY, null, l.getState().name());
            }
            if (l.getState() == PartFileLocation.LocationState.AVAILABLE && l.getStorageRootId() != null) {
                StorageRoot r = rootService.findById(l.getStorageRootId()).orElse(null);
                if (r != null && !rootService.ensureOnline(r)) return new RecoveryResult(RecoveryStatus.ROOT_OFFLINE, null, "storage root offline");
            }
        }
        if (!candidate) return new RecoveryResult(RecoveryStatus.ALREADY_AVAILABLE, null, null);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (part.getFilePath() != null) paths.add(part.getFilePath());
        for (PartFileLocation l : locations) if (l.getAbsolutePathSnapshot() != null) paths.add(l.getAbsolutePathSnapshot());
        for (String value : paths) {
            try {
                Path p = Path.of(value);
                if (!Files.isRegularFile(p)) continue;
                StorageRootService.RootMatch match = rootService.matchTrustedExisting(p).orElse(null);
                if (match == null) continue;
                long size = Files.size(match.resolvedPath());
                if (size <= 0 || (part.getFileSize() > 0 && part.getFileSize() != size)) continue;
                PartFileLocation target = locations.stream().filter(l -> Objects.equals(l.getStorageRootId(), match.root().getId())
                        && Objects.equals(l.getRelativePath(), match.relativePath())).findFirst().orElseGet(PartFileLocation::new);
                target.setPartId(partId); target.setStorageRootId(match.root().getId()); target.setRelativePath(match.relativePath());
                target.setAbsolutePathSnapshot(match.resolvedPath().toString()); target.setRole(PartFileLocation.LocationRole.PRIMARY);
                target.setState(PartFileLocation.LocationState.AVAILABLE); target.setExpectedSize(size);
                target.setLastVerifiedAt(LocalDateTime.now()); target.setErrorMessage(null); locationRepository.save(target);
                for (PartFileLocation other : locations) if (!Objects.equals(other.getId(), target.getId()) && other.getRole() == PartFileLocation.LocationRole.PRIMARY) { other.setRole(PartFileLocation.LocationRole.REPLICA); locationRepository.save(other); }
                part.setFileSize(size); part.setFilePath(match.resolvedPath().toString().replace('\\','/')); part.setUpdateTime(LocalDateTime.now()); partRepository.save(part);
                return new RecoveryResult(RecoveryStatus.RECOVERED, match.resolvedPath(), null);
            } catch (Exception e) { return new RecoveryResult(RecoveryStatus.FAILED, null, e.getClass().getSimpleName()+": "+e.getMessage()); }
        }
        return new RecoveryResult(RecoveryStatus.MISSING, null, "exact path unavailable");
    }

    public record LocalFileView(LocalFileState state, boolean available, boolean expected,
                                String primaryPath, String storageType, String rootStatus, long replicaCount,
                                String message) {}

    public record CompanionResolution(CompanionState state, Path path, Path expectedPath,
                                      Long storageRootId, String message) {
        public boolean available() {
            return state == CompanionState.AVAILABLE && path != null;
        }
    }

    private final PartFileLocationRepository locationRepository;
    private final RecordHistoryPartRepository partRepository;
    private final StorageRootService rootService;

    public PartFileLocationService(PartFileLocationRepository locationRepository,
                                   RecordHistoryPartRepository partRepository,
                                   StorageRootService rootService) {
        this.locationRepository = locationRepository;
        this.partRepository = partRepository;
        this.rootService = rootService;
    }

    @Transactional
    public PartFileLocation registerPrimary(RecordHistoryPart part) {
        if (part == null || part.getId() == null || part.getFilePath() == null || part.getFilePath().isBlank()) return null;
        Path candidate = Path.of(part.getFilePath());
        StorageRootService.RootMatch match = Files.isRegularFile(candidate)
                ? rootService.matchTrustedExisting(candidate).orElse(null)
                : rootService.matchTrustedRoot(candidate).orElse(null);
        if (match == null) return createUntrustedLegacy(part);
        PartFileLocation location = locationRepository
                .findByPartIdAndStorageRootIdAndRelativePath(part.getId(), match.root().getId(), match.relativePath())
                .orElseGet(PartFileLocation::new);
        location.setPartId(part.getId());
        location.setStorageRootId(match.root().getId());
        location.setRelativePath(match.relativePath());
        location.setAbsolutePathSnapshot(match.resolvedPath().toString());
        location.setRole(PartFileLocation.LocationRole.PRIMARY);
        boolean exists = Files.isRegularFile(match.resolvedPath());
        location.setState(exists ? PartFileLocation.LocationState.AVAILABLE
                : part.isFileDelete() ? PartFileLocation.LocationState.DELETED_BY_POLICY
                : PartFileLocation.LocationState.MISSING_UNEXPECTED);
        location.setExpectedSize(part.getFileSize());
        location.setLastVerifiedAt(LocalDateTime.now());
        location.setErrorMessage(exists ? null : "legacy file not found");
        for (PartFileLocation current : locationRepository.findByPartIdOrderByIdAsc(part.getId())) {
            if (current.getId() != null && !Objects.equals(current.getId(), location.getId())
                    && current.getRole() == PartFileLocation.LocationRole.PRIMARY) {
                current.setRole(PartFileLocation.LocationRole.REPLICA);
                locationRepository.save(current);
            }
        }
        return locationRepository.save(location);
    }

    @Transactional
    public void ensureLegacyLocation(RecordHistoryPart part) {
        if (part == null || part.getId() == null || !locationRepository.findByPartIdOrderByIdAsc(part.getId()).isEmpty()) return;
        registerPrimary(part);
    }

    @Transactional
    public FileResolution resolveReadable(Long partId) {
        RecordHistoryPart part = partRepository.findById(partId).orElse(null);
        if (part == null) return new FileResolution(LocalFileState.UNKNOWN, null, null, null, "part missing");
        ensureLegacyLocation(part);
        List<PartFileLocation> locations = locationRepository.findByPartIdOrderByIdAsc(partId);
        locations.sort(Comparator.comparing((PartFileLocation l) -> l.getRole() != PartFileLocation.LocationRole.PRIMARY)
                .thenComparing(PartFileLocation::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        StorageRoot offlineRoot = null;
        PartFileLocation offlineLocation = null;
        PartFileLocation primary = null;
        for (PartFileLocation location : locations) {
            if (location.getRole() == PartFileLocation.LocationRole.PRIMARY) primary = location;
            if (location.getState() == PartFileLocation.LocationState.DELETED_BY_POLICY
                    || location.getState() == PartFileLocation.LocationState.MOVED_AWAY
                    || location.getState() == PartFileLocation.LocationState.PROCESSING) continue;
            if (location.getStorageRootId() == null) {
                repairUntrustedLocation(part, location);
                if (location.getStorageRootId() == null) continue;
            }
            if (location.getState() == PartFileLocation.LocationState.MISSING_UNEXPECTED) {
                StorageRoot knownRoot = rootService.findById(location.getStorageRootId()).orElse(null);
                if (knownRoot != null && rootService.ensureOnline(knownRoot)) {
                    try {
                        Path knownPath = rootService.resolve(knownRoot, location.getRelativePath());
                        if (Files.isRegularFile(knownPath)
                                && (location.getExpectedSize() <= 0 || Files.size(knownPath) == location.getExpectedSize())) {
                            location.setState(PartFileLocation.LocationState.AVAILABLE);
                            location.setAbsolutePathSnapshot(knownPath.toString());
                            location.setLastVerifiedAt(LocalDateTime.now());
                            location.setErrorMessage(null);
                            locationRepository.save(location);
                        }
                    } catch (Exception ignored) {
                        // 暂时保持缺失状态，等后续精确路径检查成功再说
                    }
                }
            }
            if (location.getState() != PartFileLocation.LocationState.AVAILABLE) continue;
            StorageRoot root = rootService.findById(location.getStorageRootId()).orElse(null);
            if (root == null || !rootService.ensureOnline(root)) {
                if (offlineRoot == null) {
                    offlineRoot = root;
                    offlineLocation = location;
                }
                continue;
            }
            Path path = rootService.resolve(root, location.getRelativePath());
            if (!Files.isRegularFile(path)) {
                location.setState(PartFileLocation.LocationState.MISSING_UNEXPECTED);
                location.setErrorMessage("file not found while storage root is online");
                location.setLastVerifiedAt(LocalDateTime.now());
                locationRepository.save(location);
                continue;
            }
            location.setLastVerifiedAt(LocalDateTime.now());
            location.setAbsolutePathSnapshot(path.toString());
            locationRepository.save(location);
            if (location.getRole() != PartFileLocation.LocationRole.PRIMARY) {
                promote(part, primary, location, path);
            }
            return new FileResolution(root.getRootType() == StorageRoot.RootType.WORK
                    ? LocalFileState.AVAILABLE_WORK : LocalFileState.AVAILABLE_ARCHIVE,
                    path, location, root, null);
        }
        if (offlineLocation != null) {
            return new FileResolution(LocalFileState.ROOT_OFFLINE, null,
                    primary == null ? offlineLocation : primary, offlineRoot, "storage root offline");
        }
        PartFileLocation representative = locations.stream()
                .min(Comparator.comparingInt(location -> statePriority(location.getState())))
                .orElse(primary);
        PartFileLocation.LocationState state = representative == null ? null : representative.getState();
        return new FileResolution(mapState(state), null, representative, null, messageFor(state));
    }

    /**
     * 只修复录制管线持久化的确切路径
     * 这里特意不做目录扫描,这样对配置的驱动器根目录是安全的
     * 也不会发现无关文件
     */
    private void repairUntrustedLocation(RecordHistoryPart part, PartFileLocation location) {
        if (part.isFileDelete()) return;
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (location.getAbsolutePathSnapshot() != null) candidates.add(location.getAbsolutePathSnapshot());
        if (part.getFilePath() != null) candidates.add(part.getFilePath());
        for (String value : candidates) {
            try {
                Path candidate = Path.of(value);
                if (!Files.isRegularFile(candidate)) continue;
                StorageRootService.RootMatch match = rootService.matchTrustedExisting(candidate).orElse(null);
                if (match == null) continue;
                long size = Files.size(match.resolvedPath());
                if (size <= 0) continue;
                if (location.getExpectedSize() > 0 && size != location.getExpectedSize()) continue;
                for (PartFileLocation current : locationRepository.findByPartIdOrderByIdAsc(part.getId())) {
                    if (!Objects.equals(current.getId(), location.getId())
                            && current.getRole() == PartFileLocation.LocationRole.PRIMARY) {
                        current.setRole(PartFileLocation.LocationRole.REPLICA);
                        locationRepository.save(current);
                    }
                }
                location.setStorageRootId(match.root().getId());
                location.setRelativePath(match.relativePath());
                location.setAbsolutePathSnapshot(match.resolvedPath().toString());
                location.setRole(PartFileLocation.LocationRole.PRIMARY);
                location.setState(PartFileLocation.LocationState.AVAILABLE);
                location.setExpectedSize(size);
                location.setLastVerifiedAt(LocalDateTime.now());
                location.setErrorMessage(null);
                locationRepository.save(location);
                part.setFilePath(match.resolvedPath().toString().replace('\\', '/'));
                if (part.getFileSize() <= 0 || part.getFileSize() != size) part.setFileSize(size);
                partRepository.save(part);
                return;
            } catch (Exception ignored) {
                // 保留原始状态，尝试下一个确切持久化的路径
            }
        }
    }

    @Transactional
    public LocalFileView describe(Long partId) {
        FileResolution resolution = resolveReadable(partId);
        long replicas = locationRepository.countByPartIdAndRoleAndState(partId,
                PartFileLocation.LocationRole.REPLICA, PartFileLocation.LocationState.AVAILABLE);
        String path = resolution.path() == null
                ? resolution.location() == null ? null : resolution.location().getAbsolutePathSnapshot()
                : resolution.path().toString();
        String type = resolution.root() == null ? null : resolution.root().getRootType().name();
        String rootStatus = resolution.root() == null ? (resolution.state() == LocalFileState.ROOT_OFFLINE ? "OFFLINE" : null)
                : resolution.root().getStatus().name();
        boolean expected = resolution.state() != LocalFileState.DELETED_BY_POLICY;
        return new LocalFileView(resolution.state(), resolution.available(), expected, path, type, rootStatus, replicas,
                resolution.message());
    }

    public List<PartFileLocation> findLocations(Long partId) {
        return locationRepository.findByPartIdOrderByIdAsc(partId);
    }

    public Optional<PartFileLocation> findLocation(Long locationId) {
        return locationId == null ? Optional.empty() : locationRepository.findById(locationId);
    }

    public Optional<Path> resolveCompanion(Long partId, String extension) {
        CompanionResolution resolution = resolveCompanionState(partId, extension);
        return resolution.available() ? Optional.of(resolution.path()) : Optional.empty();
    }

    public CompanionResolution resolveCompanionState(Long partId, String extension) {
        String suffix = extension == null ? "" : extension.startsWith(".") ? extension : "." + extension;
        if (partId == null) {
            return new CompanionResolution(CompanionState.PATH_UNRESOLVED, null, null, null, "part missing");
        }
        Path expectedPath = null;
        Long offlineRootId = null;
        boolean resolvedPath = false;
        for (PartFileLocation location : locationRepository.findByPartIdOrderByIdAsc(partId)) {
            try {
                if (location.getStorageRootId() == null) {
                    Path video = legacyPath(location);
                    if (video == null) continue;
                    Path companion = companionPath(video, suffix);
                    resolvedPath = true;
                    if (expectedPath == null) expectedPath = companion;
                    if (Files.isRegularFile(companion)) {
                        return new CompanionResolution(CompanionState.AVAILABLE, companion.toRealPath(), companion,
                                null, null);
                    }
                    continue;
                }
                StorageRoot root = rootService.findById(location.getStorageRootId()).orElse(null);
                if (root == null) continue;
                if (!rootService.ensureOnline(root)) {
                    if (offlineRootId == null) offlineRootId = root.getId();
                    continue;
                }
                Path video = rootService.resolve(root, location.getRelativePath());
                Path companion = companionPath(video, suffix);
                resolvedPath = true;
                if (expectedPath == null) expectedPath = companion;
                if (Files.isRegularFile(companion)) {
                    return new CompanionResolution(CompanionState.AVAILABLE, companion.toRealPath(), companion,
                            root.getId(), null);
                }
            } catch (Exception ignored) {
            }
        }
        if (offlineRootId != null) {
            return new CompanionResolution(CompanionState.ROOT_OFFLINE, null, expectedPath, offlineRootId,
                    "storage root offline");
        }
        if (resolvedPath) {
            return new CompanionResolution(CompanionState.MISSING_UNEXPECTED, null, expectedPath, null,
                    "companion file not found");
        }
        return new CompanionResolution(CompanionState.PATH_UNRESOLVED, null, null, null,
                "companion path cannot be resolved");
    }

    public List<Path> resolveCompanions(Long partId, String extension) {
        String suffix = extension == null ? "" : extension.startsWith(".") ? extension : "." + extension;
        List<Path> result = new ArrayList<>();
        for (PartFileLocation location : locationRepository.findByPartIdOrderByIdAsc(partId)) {
            if (location.getStorageRootId() == null) continue;
            StorageRoot root = rootService.findById(location.getStorageRootId()).orElse(null);
            if (!rootService.ensureOnline(root)) continue;
            try {
                Path video = rootService.resolve(root, location.getRelativePath());
                String name = video.getFileName().toString();
                int dot = name.lastIndexOf('.');
                Path companion = video.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + suffix);
                if (Files.isRegularFile(companion)) result.add(companion.toRealPath());
            } catch (Exception ignored) {
            }
        }
        return result.stream().distinct().toList();
    }

    private static Path companionPath(Path video, String suffix) {
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return video.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + suffix);
    }

    private static Path legacyPath(PartFileLocation location) {
        if (location == null || location.getAbsolutePathSnapshot() == null || location.getAbsolutePathSnapshot().isBlank()) {
            return null;
        }
        try {
            return Path.of(location.getAbsolutePathSnapshot());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Transactional
    public PartFileLocation createProcessingTarget(Long partId, StorageRoot targetRoot, String relativePath, long size) {
        PartFileLocation target = locationRepository
                .findByPartIdAndStorageRootIdAndRelativePath(partId, targetRoot.getId(), relativePath)
                .orElseGet(PartFileLocation::new);
        target.setPartId(partId);
        target.setStorageRootId(targetRoot.getId());
        target.setRelativePath(relativePath);
        target.setAbsolutePathSnapshot(rootService.resolve(targetRoot, relativePath).toString());
        target.setRole(PartFileLocation.LocationRole.REPLICA);
        target.setState(PartFileLocation.LocationState.PROCESSING);
        target.setExpectedSize(size);
        target.setErrorMessage(null);
        return locationRepository.save(target);
    }

    @Transactional
    public void completeMove(Long partId, PartFileLocation source, PartFileLocation target, Path targetPath) {
        RecordHistoryPart part = partRepository.findById(partId).orElseThrow();
        for (PartFileLocation location : locationRepository.findByPartIdOrderByIdAsc(partId)) {
            if (!Objects.equals(location.getId(), target.getId()) && location.getRole() == PartFileLocation.LocationRole.PRIMARY) {
                location.setRole(PartFileLocation.LocationRole.REPLICA);
                if (source != null && Objects.equals(location.getId(), source.getId())) {
                    location.setState(PartFileLocation.LocationState.MOVED_AWAY);
                }
                locationRepository.save(location);
            }
        }
        target.setRole(PartFileLocation.LocationRole.PRIMARY);
        target.setState(PartFileLocation.LocationState.AVAILABLE);
        target.setAbsolutePathSnapshot(targetPath.toString());
        target.setLastVerifiedAt(LocalDateTime.now());
        target.setErrorMessage(null);
        locationRepository.save(target);
        syncLegacyPath(part, targetPath, true);
    }

    @Transactional
    public void completeCopy(PartFileLocation target, Path targetPath) {
        target.setRole(PartFileLocation.LocationRole.REPLICA);
        target.setState(PartFileLocation.LocationState.AVAILABLE);
        target.setAbsolutePathSnapshot(targetPath.toString());
        target.setLastVerifiedAt(LocalDateTime.now());
        target.setErrorMessage(null);
        locationRepository.save(target);
    }

    @Transactional
    public void completeDelete(Long partId, PartFileLocation source) {
        source.setState(PartFileLocation.LocationState.DELETED_BY_POLICY);
        source.setLastVerifiedAt(LocalDateTime.now());
        source.setErrorMessage(null);
        locationRepository.save(source);
        RecordHistoryPart part = partRepository.findById(partId).orElseThrow();
        part.setFileDelete(true);
        partRepository.save(part);
    }

    @Transactional
    public void markProcessFailed(PartFileLocation location, String error) {
        if (location == null) return;
        location.setState(PartFileLocation.LocationState.PROCESS_FAILED);
        location.setErrorMessage(abbreviate(error));
        locationRepository.save(location);
    }

    @Transactional
    public void markMissingUnexpected(PartFileLocation location, String error) {
        if (location == null) return;
        location.setState(PartFileLocation.LocationState.MISSING_UNEXPECTED);
        location.setLastVerifiedAt(LocalDateTime.now());
        location.setErrorMessage(abbreviate(error));
        locationRepository.save(location);
    }

    private void promote(RecordHistoryPart part, PartFileLocation oldPrimary, PartFileLocation replacement, Path path) {
        if (oldPrimary != null && !Objects.equals(oldPrimary.getId(), replacement.getId())) {
            oldPrimary.setRole(PartFileLocation.LocationRole.REPLICA);
            locationRepository.save(oldPrimary);
        }
        replacement.setRole(PartFileLocation.LocationRole.PRIMARY);
        locationRepository.save(replacement);
        syncLegacyPath(part, path, false);
    }

    private void syncLegacyPath(RecordHistoryPart part, Path path, boolean processed) {
        part.setFilePath(path.toString().replace('\\', '/'));
        if (processed) part.setFileDelete(true);
        partRepository.save(part);
    }

    private PartFileLocation createUntrustedLegacy(RecordHistoryPart part) {
        for (PartFileLocation current : locationRepository.findByPartIdOrderByIdAsc(part.getId())) {
            if (current.getRole() == PartFileLocation.LocationRole.PRIMARY) {
                current.setRole(PartFileLocation.LocationRole.REPLICA);
                locationRepository.save(current);
            }
        }
        PartFileLocation location = new PartFileLocation();
        location.setPartId(part.getId());
        location.setStorageRootId(null);
        String fileName;
        try { fileName = Path.of(part.getFilePath()).getFileName().toString(); }
        catch (Exception e) { fileName = "unknown-" + part.getId(); }
        location.setRelativePath(fileName);
        location.setAbsolutePathSnapshot(part.getFilePath());
        location.setRole(PartFileLocation.LocationRole.PRIMARY);
        boolean exists = false;
        try { exists = Files.isRegularFile(Path.of(part.getFilePath())); } catch (Exception ignored) {}
        location.setState(exists ? PartFileLocation.LocationState.PROCESS_FAILED
                : part.isFileDelete() ? PartFileLocation.LocationState.DELETED_BY_POLICY
                : PartFileLocation.LocationState.MISSING_UNEXPECTED);
        location.setExpectedSize(part.getFileSize());
        location.setErrorMessage(exists ? "file is outside trusted storage roots" : "legacy file not found");
        return locationRepository.save(location);
    }

    private static LocalFileState mapState(PartFileLocation.LocationState state) {
        if (state == null) return LocalFileState.UNKNOWN;
        return switch (state) {
            case DELETED_BY_POLICY, MOVED_AWAY -> LocalFileState.DELETED_BY_POLICY;
            case MISSING_UNEXPECTED -> LocalFileState.MISSING_UNEXPECTED;
            case PROCESSING -> LocalFileState.PROCESSING;
            case PROCESS_FAILED -> LocalFileState.PROCESS_FAILED;
            case AVAILABLE -> LocalFileState.UNKNOWN;
        };
    }

    private static int statePriority(PartFileLocation.LocationState state) {
        if (state == null) return 6;
        return switch (state) {
            case PROCESSING -> 0;
            case PROCESS_FAILED -> 1;
            case MISSING_UNEXPECTED -> 2;
            case AVAILABLE -> 3;
            case DELETED_BY_POLICY -> 4;
            case MOVED_AWAY -> 5;
        };
    }

    private static String messageFor(PartFileLocation.LocationState state) {
        if (state == null) return "no local file location";
        return switch (state) {
            case DELETED_BY_POLICY, MOVED_AWAY -> "file removed by configured policy";
            case MISSING_UNEXPECTED -> "file unexpectedly missing";
            case PROCESSING -> "file operation in progress";
            case PROCESS_FAILED -> "file operation failed";
            case AVAILABLE -> "file unavailable";
        };
    }

    private static String abbreviate(String error) {
        if (error == null) return null;
        return error.length() <= 512 ? error : error.substring(0, 512);
    }
}
