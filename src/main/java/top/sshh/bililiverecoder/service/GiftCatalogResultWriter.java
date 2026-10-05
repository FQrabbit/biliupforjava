package top.sshh.bililiverecoder.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveGiftCatalog;
import top.sshh.bililiverecoder.repo.RoomLiveGiftCatalogRepository;
import java.util.List;

@Service
public class GiftCatalogResultWriter {
    private final RoomLiveGiftCatalogRepository repository;
    private final ObjectProvider<StatsUpdateService> updates;

    public GiftCatalogResultWriter(RoomLiveGiftCatalogRepository repository, ObjectProvider<StatsUpdateService> updates) {
        this.repository = repository;
        this.updates = updates;
    }

    @Transactional
    public void saveChanged(List<RoomLiveGiftCatalog> candidates) {
        List<RoomLiveGiftCatalog> changed = new java.util.ArrayList<>();
        for (var candidate : candidates) {
            var previous = repository.findByRoomIdAndGiftId(candidate.getRoomId(), candidate.getGiftId());
            if (StatsValues.same(previous, candidate, "updatedAt", "id")) continue;
            candidate.setId(previous == null ? null : previous.getId());
            if (previous != null && !java.util.Objects.equals(previous.getGiftName(), candidate.getGiftName())) {
                var old = new RoomLiveGiftCatalog();
                org.springframework.beans.BeanUtils.copyProperties(previous, old);
                changed.add(old);
            }
            repository.save(candidate);
            changed.add(candidate);
        }
        if (!changed.isEmpty()) updates.getObject().priceChanged(changed);
    }
}
