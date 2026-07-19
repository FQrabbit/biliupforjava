package top.sshh.bililiverecoder.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
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
    public static final String MIGRATION_VERSION = "1";
    private static final int BATCH_SIZE = 500;

    private final SystemConfigRepository configRepository;
    private final RecordRoomRepository roomRepository;
    private final RecordHistoryPartRepository partRepository;
    private final PartFileLocationRepository locationRepository;
    private final StorageRootService rootService;
    private final PartFileLocationService locationService;

    public StorageLifecycleMigrationService(SystemConfigRepository configRepository,
                                            RecordRoomRepository roomRepository,
                                            RecordHistoryPartRepository partRepository,
                                            PartFileLocationRepository locationRepository,
                                            StorageRootService rootService,
                                            PartFileLocationService locationService) {
        this.configRepository = configRepository;
        this.roomRepository = roomRepository;
        this.partRepository = partRepository;
        this.locationRepository = locationRepository;
        this.rootService = rootService;
        this.locationService = locationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateAfterStartup() {
        if (configRepository.findById(MIGRATION_KEY)
                .map(SystemConfig::getConfigValue).filter(MIGRATION_VERSION::equals).isPresent()) return;
        long started = System.nanoTime();
        int migrated = 0;
        try {
            for (RecordRoom room : roomRepository.findAllOrderBySortOrder()) {
                if (StringUtils.isNotBlank(room.getMoveDir())) {
                    rootService.getOrCreateArchiveRoot(room.getMoveDir());
                }
            }
            long lastId = 0L;
            while (true) {
                List<RecordHistoryPart> batch = partRepository.findByIdGreaterThanOrderByIdAsc(
                        lastId, PageRequest.of(0, BATCH_SIZE));
                if (batch.isEmpty()) break;
                for (RecordHistoryPart part : batch) {
                    lastId = Math.max(lastId, part.getId());
                    if (!locationRepository.existsByPartId(part.getId())) {
                        locationService.ensureLegacyLocation(part);
                        migrated++;
                    }
                }
                if (batch.size() < BATCH_SIZE) break;
            }
            SystemConfig marker = configRepository.findById(MIGRATION_KEY).orElseGet(SystemConfig::new);
            marker.setConfigKey(MIGRATION_KEY);
            marker.setConfigValue(MIGRATION_VERSION);
            marker.setDescription("Storage root and part file location migration version");
            configRepository.save(marker);
            log.info("[BLR] {}", LogKvs.event("StorageLifecycle.Migration.Done")
                    .add("version", MIGRATION_VERSION).add("migrated", migrated)
                    .addStageCostMs("total", started));
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("StorageLifecycle.Migration.Failed")
                    .add("version", MIGRATION_VERSION).add("migrated", migrated)
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
