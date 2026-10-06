package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;

@Service
public class HistoryUploadAdmissionService {
    private final RecordHistoryRepository histories;
    private final PartActivityCoordinator activities;

    public HistoryUploadAdmissionService(RecordHistoryRepository histories, PartActivityCoordinator activities) {
        this.histories = histories;
        this.activities = activities;
    }

    @Transactional
    public boolean tryRegisterUpload(Long historyId, Long partId) {
        if (historyId == null || partId == null) return false;
        RecordHistory history = histories.findByIdForUpdate(historyId).orElse(null);
        if (HistoryProcessingPolicy.blocksAutomatic(history)) return false;
        return activities.tryRegisterUpload(partId, historyId);
    }

    @Transactional
    public boolean tryRegisterHistoryUpload(Long historyId) {
        if (historyId == null) return false;
        RecordHistory history = histories.findByIdForUpdate(historyId).orElse(null);
        if (HistoryProcessingPolicy.blocksAutomatic(history)) return false;
        return activities.tryRegisterHistoryUpload(historyId);
    }
}
