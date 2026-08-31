package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.SystemConfig;
import top.sshh.bililiverecoder.repo.PartFileLocationRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.repo.SystemConfigRepository;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.List;

@Slf4j
@Service
public class StorageLifecycleMigrationService {

    public static final String MIGRATION_KEY = "storage.lifecycle.migration.version";
    public static final String MIGRATION_VERSION = "2";
    private static final int BATCH_SIZE = 500;

    private final SystemConfigRepository configRepository;
    private final RecordRoomRepository roomRepository;
    private final RecordHistoryPartRepository partRepository;
    private final PartFileLocationRepository locationRepository;
    private final StorageRootService rootService;
    private final PartFileLocationService locationService;
    private final ObjectProvider<TaskExecutor> taskExecutor;

    public StorageLifecycleMigrationService(SystemConfigRepository configRepository,
                                            RecordRoomRepository roomRepository,
                                            RecordHistoryPartRepository partRepository,
                                            PartFileLocationRepository locationRepository,
                                            StorageRootService rootService,
                                            PartFileLocationService locationService,
                                            ObjectProvider<TaskExecutor> taskExecutor) {
        this.configRepository = configRepository;
        this.roomRepository = roomRepository;
        this.partRepository = partRepository;
        this.locationRepository = locationRepository;
        this.rootService = rootService;
        this.locationService = locationService;
        this.taskExecutor = taskExecutor;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAfterStartup() {
        if (configRepository.findById(MIGRATION_KEY)
                .map(SystemConfig::getConfigValue).filter(MIGRATION_VERSION::equals).isPresent()) return;
        TaskExecutor executor = taskExecutor.getIfAvailable();
        if (executor == null) { runMigration(); return; }
        executor.execute(this::runMigration);
    }

    private void runMigration() {
        long started = System.nanoTime();
        int scanned = 0, recovered = 0, uploadReset = 0, missing = 0, offline = 0, skipped = 0, failed = 0;
        try {
            for (RecordRoom room : roomRepository.findAllOrderBySortOrder()) {
                if (StringUtils.isNotBlank(room.getMoveDir())) {
                    rootService.getOrCreateArchiveRoot(room.getMoveDir());
                }
            }
            long lastId = 0L;
            while (true) {
                List<RecordHistoryPart> batch = partRepository.findStorageRecoveryCandidatesAfterId(
                        lastId, PageRequest.of(0, BATCH_SIZE));
                if (batch.isEmpty()) break;
                for (RecordHistoryPart part : batch) {
                    lastId = Math.max(lastId, part.getId());
                    scanned++;
                    PartFileLocationService.RecoveryResult rr = locationService.recoverExact(part.getId());
                    switch (rr.status()) {
                        case RECOVERED -> { recovered++; if ("FILE_MISSING".equals(part.getDeleteFailType()) && part.getUploadRetryCount() >= 9999) { part.setUploadRetryCount(0); part.setDeleteFailType(null); part.setDeleteFailReason(null); part.setUpdateTime(java.time.LocalDateTime.now()); partRepository.save(part); uploadReset++; } }
                        case ROOT_OFFLINE -> offline++;
                        case MISSING -> missing++;
                        case SKIPPED_POLICY, ALREADY_AVAILABLE -> skipped++;
                        case FAILED -> failed++;
                    }
                }
                if (batch.size() < BATCH_SIZE) break;
            }
            if (offline == 0 && failed == 0) {
                SystemConfig marker = configRepository.findById(MIGRATION_KEY).orElseGet(SystemConfig::new);
                marker.setConfigKey(MIGRATION_KEY);
                marker.setConfigValue(MIGRATION_VERSION);
                marker.setDescription("Storage root and part file location migration version");
                configRepository.save(marker);
            }
            log.info("[BLR] {}", LogKvs.event("StorageLifecycle.Migration.Done")
                    .add("version", MIGRATION_VERSION).add("scanned", scanned).add("recovered", recovered)
                    .add("uploadReset", uploadReset).add("missing", missing).add("offline", offline).add("skipped", skipped).add("failed", failed)
                    .addStageCostMs("total", started));
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("StorageLifecycle.Migration.Failed")
                    .add("version", MIGRATION_VERSION).add("scanned", scanned)
                    .add("err", e.getMessage()).add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", started), e);
        }
    }

    public void migratePart(RecordHistoryPart part) {
        if (part != null && part.getId() != null && !locationRepository.existsByPartId(part.getId())) {
            locationService.ensureLegacyLocation(part);
        }
    }
}
