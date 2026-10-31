package top.sshh.bililiverecoder.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void deleteByRoomIdDeletesOnlyTargetRoomAndCanBeRetried() {
        RoomLiveEventXmlIssue first = new RoomLiveEventXmlIssue();
        first.setPartId(101L);
        first.setRoomId("123");
        first.setIssueType(RoomLiveEventXmlIssue.IssueType.INVALID_XML);
        issueRepository.save(first);

        RoomLiveEventXmlIssue second = new RoomLiveEventXmlIssue();
        second.setPartId(102L);
        second.setRoomId("123");
        second.setIssueType(RoomLiveEventXmlIssue.IssueType.MISSING_UNEXPECTED);
        issueRepository.save(second);

        RoomLiveEventXmlIssue otherRoom = new RoomLiveEventXmlIssue();
        otherRoom.setPartId(103L);
        otherRoom.setRoomId("456");
        otherRoom.setIssueType(RoomLiveEventXmlIssue.IssueType.INVALID_XML);
        issueRepository.save(otherRoom);
        entityManager.flush();
        entityManager.clear();

        assertEquals(2, issueRepository.deleteByRoomId("123"));
        assertFalse(issueRepository.existsById(101L));
        assertFalse(issueRepository.existsById(102L));
        assertTrue(issueRepository.existsById(103L));
        assertEquals(0, issueRepository.deleteByRoomId("123"));
        assertEquals(0, issueRepository.deleteByRoomId("missing"));
        assertEquals(1L, issueRepository.count());
    }

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
