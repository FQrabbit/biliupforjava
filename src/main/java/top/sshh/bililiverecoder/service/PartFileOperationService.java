package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.PartFileOperation;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.PartFileOperationRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PartFileOperationService {

    private static final Set<PartFileOperation.OperationStatus> ACTIVE = Set.of(
            PartFileOperation.OperationStatus.PENDING, PartFileOperation.OperationStatus.RUNNING);
    private static final Set<PartFileOperation.OperationStatus> COMPLETE = Set.of(
            PartFileOperation.OperationStatus.SUCCEEDED,
            PartFileOperation.OperationStatus.SUCCEEDED_WITH_WARNINGS);

    private final PartFileOperationRepository operationRepository;
    private final PartFileLocationRepository locationRepository;
    private final PartFileLocationService locationService;
    private final StorageRootService rootService;
    private final PartFileStorageAdapter storage;
    private final Map<Long, Object> partLocks = new ConcurrentHashMap<>();

    public PartFileOperationService(PartFileOperationRepository operationRepository,
                                    PartFileLocationRepository locationRepository,
                                    PartFileLocationService locationService,
                                    StorageRootService rootService,
                                    PartFileStorageAdapter storage) {
        this.operationRepository = operationRepository;
        this.locationRepository = locationRepository;
        this.locationService = locationService;
        this.rootService = rootService;
        this.storage = storage;
    }

    public PartFileOperation move(Long partId, String targetRootPath) {
        return submit(partId, PartFileOperation.OperationType.MOVE, targetRootPath);
    }

    public PartFileOperation copy(Long partId, String targetRootPath) {
        return submit(partId, PartFileOperation.OperationType.COPY, targetRootPath);
    }

    public PartFileOperation delete(Long partId) {
        return submit(partId, PartFileOperation.OperationType.DELETE, null);
    }

    public List<String> deleteAllAvailable(Long partId) {
        List<String> failures = new ArrayList<>();
        for (PartFileLocation location : locationService.findLocations(partId)) {
            if (location.getState() != PartFileLocation.LocationState.AVAILABLE) continue;
            StorageRoot root = rootService.findById(location.getStorageRootId()).orElse(null);
            if (!rootService.isOnline(root)) {
                failures.add(location.getAbsolutePathSnapshot() + "：存储目录离线");
                continue;
            }
            try {
                Path path = rootService.resolve(root, location.getRelativePath());
                storage.delete(path);
                if (storage.isRegularFile(path)) throw new IOException("video still exists after delete");
                locationService.completeDelete(partId, location);
            } catch (Exception e) {
                failures.add(location.getAbsolutePathSnapshot() + "：" + String.valueOf(e.getMessage()));
            }
        }
        return failures;
    }

    @Transactional
    public void purgeMetadata(Long partId) {
        operationRepository.deleteByPartId(partId);
        locationRepository.deleteByPartId(partId);
    }

    public PartFileOperation retry(String operationKey) {
        PartFileOperation operation = operationRepository.findByOperationKey(operationKey)
                .orElseThrow(() -> new IllegalArgumentException("file operation missing"));
        if (operation.getStatus() == PartFileOperation.OperationStatus.RUNNING) return operation;
        if (operation.getStatus() == PartFileOperation.OperationStatus.PENDING) {
            return executeLocked(operation);
        }
        operation.setStatus(PartFileOperation.OperationStatus.PENDING);
        operation.setErrorMessage(null);
        operation.setFinishedAt(null);
        operationRepository.save(operation);
        return executeLocked(operation);
    }

    public Optional<PartFileOperation> latest(Long partId) {
        return operationRepository.findFirstByPartIdOrderByCreatedAtDesc(partId);
    }

    public PartFileOperation submit(Long partId, PartFileOperation.OperationType type, String targetRootPath) {
        if (partId == null) throw new IllegalArgumentException("partId missing");
        Object lock = partLocks.computeIfAbsent(partId, ignored -> new Object());
        synchronized (lock) {
            try {
                Optional<PartFileOperation> active = operationRepository
                        .findFirstByPartIdAndStatusInOrderByCreatedAtDesc(partId, ACTIVE);
                if (active.isPresent()) return active.get();

                StorageRoot targetRoot = targetRootPath == null || targetRootPath.isBlank()
                        ? null : rootService.getOrCreateArchiveRoot(targetRootPath);
                PartFileLocationService.FileResolution source = locationService.resolveReadable(partId);
                Optional<PartFileOperation> previous = operationRepository
                        .findFirstByPartIdAndOperationTypeOrderByCreatedAtDesc(partId, type);
                PartFileLocation requestSource = source.location();
                if (previous.filter(operation -> isSameCompletedRequest(operation, targetRoot, requestSource)).isPresent()) {
                    return previous.get();
                }

                PartFileOperation operation = new PartFileOperation();
                operation.setPartId(partId);
                operation.setOperationType(type);
                operation.setSourceLocationId(source.location() == null ? null : source.location().getId());
                if (targetRoot != null) {
                    operation.setTargetRootId(targetRoot.getId());
                    if (source.location() != null) {
                        operation.setTargetRelativePath(source.location().getRelativePath());
                    }
                }
                operation = operationRepository.save(operation);
                if (rootService.hasPendingWorkPathChange()) {
                    return pending(operation, "work path change is pending confirmation");
                }
                if (source.state() == PartFileLocationService.LocalFileState.ROOT_OFFLINE) {
                    return pending(operation, "storage root offline");
                }
                if (!source.available()) {
                    return fail(operation, null, source.message());
                }
                return execute(operation);
            } finally {
                partLocks.remove(partId, lock);
            }
        }
    }

    @Scheduled(fixedDelay = 300000, initialDelay = 90000)
    public void recoverPendingOperations() {
        if (rootService.hasPendingWorkPathChange()) return;
        for (PartFileOperation operation : operationRepository.findByStatusInOrderByCreatedAtAsc(ACTIVE)) {
            try {
                executeLocked(operation);
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("PartFile.Operation.RecoveryFailed")
                        .add("operationKey", operation.getOperationKey()).add("partId", operation.getPartId())
                        .add("err", e.getMessage()));
            }
        }
    }

    private PartFileOperation executeLocked(PartFileOperation operation) {
        Long partId = operation.getPartId();
        Object lock = partLocks.computeIfAbsent(partId, ignored -> new Object());
        synchronized (lock) {
            try {
                return execute(operation);
            } finally {
                partLocks.remove(partId, lock);
            }
        }
    }

    private PartFileOperation execute(PartFileOperation operation) {
        operation = operationRepository.findById(operation.getId()).orElseThrow();
        if (COMPLETE.contains(operation.getStatus())) return operation;
        boolean recoveringRunningOperation = operation.getStatus() == PartFileOperation.OperationStatus.RUNNING;
        operation.setStatus(PartFileOperation.OperationStatus.RUNNING);
        operation.setStartedAt(LocalDateTime.now());
        operation.setAttemptCount(operation.getAttemptCount() + 1);
        operation.setErrorMessage(null);
        operation = operationRepository.save(operation);
        try {
            return switch (operation.getOperationType()) {
                case DELETE -> executeDelete(operation, recoveringRunningOperation);
                case MOVE, COPY -> executeTransfer(operation);
            };
        } catch (Exception e) {
            return fail(operation, findTarget(operation).orElse(null),
                    e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage()));
        }
    }

    private PartFileOperation executeDelete(PartFileOperation operation, boolean recoveringRunningOperation) throws IOException {
        PartFileLocation source = locationService.findLocation(operation.getSourceLocationId()).orElse(null);
        if (source == null) return fail(operation, null, "source file location missing");
        if (source.getState() == PartFileLocation.LocationState.DELETED_BY_POLICY) {
            return succeed(operation, false, null);
        }
        LocationPath sourcePath = locate(source);
        if (sourcePath.offline()) return pending(operation, "storage root offline");
        if (!storage.isRegularFile(sourcePath.path())) {
            if (recoveringRunningOperation) {
                locationService.completeDelete(operation.getPartId(), source);
                return succeed(operation, false, null);
            }
            locationService.markMissingUnexpected(source, "source video missing before delete");
            return fail(operation, null, "source video missing before delete");
        }
        storage.delete(sourcePath.path());
        if (storage.isRegularFile(sourcePath.path())) throw new IOException("source video still exists after delete");
        locationService.completeDelete(operation.getPartId(), source);
        return succeed(operation, false, null);
    }

    private PartFileOperation executeTransfer(PartFileOperation operation) throws IOException {
        StorageRoot targetRoot = rootService.findById(operation.getTargetRootId())
                .orElseThrow(() -> new IOException("target storage root missing"));
        rootService.markOnline(targetRoot);
        if (!rootService.isOnline(targetRoot) || !targetRoot.isWritable()) {
            return pending(operation, "target storage root is offline or read-only");
        }

        PartFileLocation source = locationService.findLocation(operation.getSourceLocationId()).orElse(null);
        String relative = operation.getTargetRelativePath();
        if ((relative == null || relative.isBlank()) && source != null) relative = source.getRelativePath();
        if (relative == null || relative.isBlank()) return fail(operation, null, "target relative path missing");
        Path targetVideo = rootService.resolve(targetRoot, relative);
        Optional<PartFileLocation> existingTarget = locationRepository
                .findByPartIdAndStorageRootIdAndRelativePath(operation.getPartId(), targetRoot.getId(), relative);
        PartFileLocation target = existingTarget.orElse(null);
        long expectedSize = target == null ? 0 : target.getExpectedSize();
        if (expectedSize <= 0 && source != null) expectedSize = source.getExpectedSize();

        LocationPath sourcePath = source == null ? null : locate(source);
        if (expectedSize <= 0 && sourcePath != null && storage.isRegularFile(sourcePath.path())) {
            expectedSize = storage.size(sourcePath.path());
        }
        if (sourcePath != null && sourcePath.path() != null && samePath(sourcePath.path(), targetVideo)) {
            return succeed(operation, false, "source and target are identical; no file operation required");
        }

        boolean targetExists = storage.isRegularFile(targetVideo);
        if (targetExists && expectedSize <= 0) expectedSize = storage.size(targetVideo);
        boolean targetValid = targetExists && storage.size(targetVideo) == expectedSize;
        if (targetExists && !targetValid) {
            if (target == null) {
                target = locationService.createProcessingTarget(
                        operation.getPartId(), targetRoot, relative, expectedSize);
            }
            return fail(operation, target, "target file exists with a different size");
        }

        if (targetValid) {
            if (target == null) {
                target = locationService.createProcessingTarget(
                        operation.getPartId(), targetRoot, relative, expectedSize);
            }
            return reconcileVerifiedTarget(operation, source, sourcePath, target, targetVideo);
        }

        if (source == null) return fail(operation, target, "source file location missing");
        if (sourcePath.offline()) return pending(operation, "storage root offline");
        if (!storage.isRegularFile(sourcePath.path())) {
            locationService.markMissingUnexpected(source, "source video missing while storage root is online");
            return fail(operation, target, "source video missing and target is incomplete");
        }

        expectedSize = storage.size(sourcePath.path());
        target = locationService.createProcessingTarget(
                operation.getPartId(), targetRoot, relative, expectedSize);
        operation.setTargetRelativePath(relative);
        operationRepository.save(operation);
        storage.createDirectories(targetVideo.getParent());
        if (operation.getOperationType() == PartFileOperation.OperationType.MOVE) {
            storage.move(sourcePath.path(), targetVideo);
        } else {
            storage.copy(sourcePath.path(), targetVideo);
        }
        if (!storage.isRegularFile(targetVideo) || storage.size(targetVideo) != expectedSize) {
            return fail(operation, target, "target video verification failed");
        }
        return finishTransfer(operation, source, sourcePath, target, targetVideo);
    }

    private PartFileOperation reconcileVerifiedTarget(PartFileOperation operation,
                                                      PartFileLocation source,
                                                      LocationPath sourcePath,
                                                      PartFileLocation target,
                                                      Path targetVideo) throws IOException {
        if (operation.getOperationType() == PartFileOperation.OperationType.MOVE
                && sourcePath != null && storage.isRegularFile(sourcePath.path())) {
            List<String> warnings = transferCompanions(sourcePath.path(), targetVideo, operation.getOperationType());
            storage.delete(sourcePath.path());
            if (storage.isRegularFile(sourcePath.path())) throw new IOException("source remains after recovered move");
            locationService.completeMove(operation.getPartId(), source, target, targetVideo);
            return succeed(operation, !warnings.isEmpty(), joinWarnings(warnings));
        }
        if (operation.getOperationType() == PartFileOperation.OperationType.MOVE) {
            locationService.completeMove(operation.getPartId(), source, target, targetVideo);
        } else {
            locationService.completeCopy(target, targetVideo);
        }
        return succeed(operation, false, null);
    }

    private PartFileOperation finishTransfer(PartFileOperation operation,
                                             PartFileLocation source,
                                             LocationPath sourcePath,
                                             PartFileLocation target,
                                             Path targetVideo) throws IOException {
        List<String> warnings = transferCompanions(sourcePath.path(), targetVideo, operation.getOperationType());
        if (operation.getOperationType() == PartFileOperation.OperationType.MOVE) {
            locationService.completeMove(operation.getPartId(), source, target, targetVideo);
        } else {
            locationService.completeCopy(target, targetVideo);
        }
        return succeed(operation, !warnings.isEmpty(), joinWarnings(warnings));
    }

    private List<String> transferCompanions(Path sourceVideo, Path targetVideo,
                                            PartFileOperation.OperationType type) throws IOException {
        List<String> warnings = new ArrayList<>();
        for (Path companion : companionFiles(sourceVideo)) {
            Path companionTarget = targetVideo.getParent().resolve(companion.getFileName());
            if (storage.isRegularFile(companionTarget)) continue;
            try {
                if (type == PartFileOperation.OperationType.MOVE) storage.move(companion, companionTarget);
                else storage.copy(companion, companionTarget);
            } catch (Exception e) {
                warnings.add(companion.getFileName() + ":" + e.getMessage());
            }
        }
        return warnings;
    }

    private LocationPath locate(PartFileLocation location) {
        StorageRoot root = rootService.findById(location.getStorageRootId()).orElse(null);
        if (!rootService.ensureOnline(root)) return new LocationPath(location, root, null, true);
        return new LocationPath(location, root, rootService.resolve(root, location.getRelativePath()), false);
    }

    private Optional<PartFileLocation> findTarget(PartFileOperation operation) {
        if (operation.getTargetRootId() == null || operation.getTargetRelativePath() == null) return Optional.empty();
        return locationRepository.findByPartIdAndStorageRootIdAndRelativePath(
                operation.getPartId(), operation.getTargetRootId(), operation.getTargetRelativePath());
    }

    private boolean isSameCompletedRequest(PartFileOperation operation, StorageRoot targetRoot,
                                           PartFileLocation source) {
        if (!COMPLETE.contains(operation.getStatus())) return false;
        Long targetId = targetRoot == null ? null : targetRoot.getId();
        if (!Objects.equals(operation.getTargetRootId(), targetId)) return false;
        if (operation.getOperationType() == PartFileOperation.OperationType.DELETE) {
            return source == null || Objects.equals(operation.getSourceLocationId(), source.getId());
        }
        return findTarget(operation)
                .filter(location -> location.getState() == PartFileLocation.LocationState.AVAILABLE)
                .isPresent();
    }

    private static List<Path> companionFiles(Path video) throws IOException {
        Path parent = video.getParent();
        String name = video.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        if (parent == null || !Files.isDirectory(parent)) return List.of();
        try (var stream = Files.list(parent)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String candidate = path.getFileName().toString();
                        return !candidate.equals(name)
                                && (candidate.startsWith(stem + ".") || candidate.startsWith(stem + "_"));
                    })
                    .toList();
        }
    }

    private PartFileOperation pending(PartFileOperation operation, String message) {
        operation.setStatus(PartFileOperation.OperationStatus.PENDING);
        operation.setErrorMessage(abbreviate(message));
        operation.setFinishedAt(null);
        PartFileOperation saved = operationRepository.save(operation);
        log.info("[BLR] {}", LogKvs.event("PartFile.Operation.Pending")
                .add("operationKey", saved.getOperationKey()).add("partId", saved.getPartId())
                .add("operationType", saved.getOperationType()).add("reason", message));
        return saved;
    }

    private PartFileOperation succeed(PartFileOperation operation, boolean warnings, String message) {
        operation.setStatus(warnings ? PartFileOperation.OperationStatus.SUCCEEDED_WITH_WARNINGS
                : PartFileOperation.OperationStatus.SUCCEEDED);
        operation.setErrorMessage(abbreviate(message));
        operation.setFinishedAt(LocalDateTime.now());
        PartFileOperation saved = operationRepository.save(operation);
        log.info("[BLR] {}", LogKvs.event("PartFile.Operation.Succeeded")
                .add("operationKey", saved.getOperationKey()).add("partId", saved.getPartId())
                .add("operationType", saved.getOperationType()).add("warnings", warnings));
        return saved;
    }

    private PartFileOperation fail(PartFileOperation operation, PartFileLocation target, String message) {
        if (target != null) locationService.markProcessFailed(target, message);
        operation.setStatus(PartFileOperation.OperationStatus.FAILED);
        operation.setErrorMessage(abbreviate(message));
        operation.setFinishedAt(LocalDateTime.now());
        PartFileOperation saved = operationRepository.save(operation);
        log.error("[BLR] {}", LogKvs.event("PartFile.Operation.Failed")
                .add("operationKey", saved.getOperationKey()).add("partId", saved.getPartId())
                .add("operationType", saved.getOperationType()).add("err", message));
        return saved;
    }

    private static boolean samePath(Path left, Path right) {
        return StorageRootService.samePath(left.toString(), right.toString());
    }

    private static String joinWarnings(List<String> warnings) {
        return warnings.isEmpty() ? null : String.join("; ", warnings);
    }

    private static String abbreviate(String message) {
        if (message == null) return null;
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private record LocationPath(PartFileLocation location, StorageRoot root, Path path, boolean offline) {}
}
