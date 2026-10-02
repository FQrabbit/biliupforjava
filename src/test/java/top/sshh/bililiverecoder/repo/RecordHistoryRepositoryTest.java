package top.sshh.bililiverecoder.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import top.sshh.bililiverecoder.entity.RecordHistory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:record-history-repo;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecordHistoryRepositoryTest {

    @Autowired
    private RecordHistoryRepository repository;
    @Autowired
    private RecordHistoryPartRepository partRepository;

    @Test
    void partSummaryReturnsLatestEndAndCountsUnfinishedPartsEvenIfUploaded() {
        java.time.LocalDateTime end = java.time.LocalDateTime.of(2026, 10, 2, 4, 0);
        RecordHistory history = repository.save(new RecordHistory());
        top.sshh.bililiverecoder.entity.RecordHistoryPart ended = new top.sshh.bililiverecoder.entity.RecordHistoryPart();
        ended.setHistoryId(history.getId());
        ended.setEndTime(end);
        top.sshh.bililiverecoder.entity.RecordHistoryPart recording = new top.sshh.bililiverecoder.entity.RecordHistoryPart();
        recording.setHistoryId(history.getId());
        recording.setUpload(true);
        recording.setRecording(true);
        partRepository.saveAll(List.of(ended, recording));
        Object[] row = partRepository.aggregateListStatsByHistoryIds(List.of(history.getId())).get(0);
        assertEquals(1L, ((Number) row[5]).longValue());
        assertEquals(end, row[9]);
        assertEquals(end, partRepository.findLatestEndTimeByHistoryId(history.getId()));
    }

    @Test
    void syncListExcludesHistoryWhileEditPartsAreUploading() {
        RecordHistory uploading = history("BV-UPLOADING", true);
        RecordHistory pendingReview = history("BV-PENDING", false);
        repository.saveAll(List.of(uploading, pendingReview));

        List<RecordHistory> result = repository.findSyncList();

        assertEquals(List.of("BV-PENDING"), result.stream().map(RecordHistory::getBvId).toList());
    }

    private RecordHistory history(String bvId, boolean editPartsUploading) {
        RecordHistory history = new RecordHistory();
        history.setBvId(bvId);
        history.setPublish(true);
        history.setCode(-1);
        history.setEditPartsUploading(editPartsUploading);
        return history;
    }
}
