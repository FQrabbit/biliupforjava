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
