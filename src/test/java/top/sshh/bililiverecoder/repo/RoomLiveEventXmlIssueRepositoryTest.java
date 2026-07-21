package top.sshh.bililiverecoder.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:xml-issue-repo;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "logging.file.name=target/test-logs/xml-issue-repo.log"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RoomLiveEventXmlIssueRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private RoomLiveEventXmlIssueRepository issueRepository;

    @Test
    void deleteByPartIdIfPresentIsIdempotent() {
        long partId = 42L;

        assertEquals(0, issueRepository.deleteByPartIdIfPresent(partId));

        RoomLiveEventXmlIssue issue = new RoomLiveEventXmlIssue();
        issue.setPartId(partId);
        issue.setIssueType(RoomLiveEventXmlIssue.IssueType.INVALID_XML);
        issueRepository.save(issue);
        entityManager.flush();
        entityManager.clear();

        assertEquals(1, issueRepository.deleteByPartIdIfPresent(partId));
        entityManager.flush();
        entityManager.clear();
        assertFalse(issueRepository.existsById(partId));
        assertEquals(0, issueRepository.deleteByPartIdIfPresent(partId));
    }
}
