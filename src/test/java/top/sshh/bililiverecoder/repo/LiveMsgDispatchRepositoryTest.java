package top.sshh.bililiverecoder.repo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import top.sshh.bililiverecoder.entity.LiveMsg;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:dispatch-repo;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LiveMsgDispatchRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private LiveMsgRepository liveMsgRepository;

    @Test
    void pendingNormalDispatchQueryOnlyReturnsEnabledPendingNormalParts() {
        Long eligiblePartId = createDispatchCase("room-normal-ok", true, true, true, 0, 0, -1, 1L);
        createDispatchCase("room-normal-disabled", false, true, true, 0, 0, -1, 1L);
        createDispatchCase("room-normal-sent", true, true, true, 0, 0, 0, 1L);
        createDispatchCase("room-normal-high-pool", true, true, true, 0, 1, -1, 1L);
        createDispatchCase("room-normal-missing-cid", true, true, true, 0, 0, -1, null);
        createDispatchCase("room-normal-not-published", true, true, false, 0, 0, -1, 1L);

        entityManager.flush();
        entityManager.clear();

        List<Long> partIds = liveMsgRepository.findPendingNormalDispatchPartIds(PageRequest.of(0, 20));

        assertEquals(List.of(eligiblePartId), partIds);
    }

    @Test
    void pendingHighDispatchQueryOnlyReturnsEnabledPendingHighParts() {
        Long eligiblePartId = createDispatchCase("room-high-ok", true, true, true, 0, 1, -1, 1L);
        createDispatchCase("room-high-disabled", true, false, true, 0, 1, -1, 1L);
        createDispatchCase("room-high-normal-pool", true, true, true, 0, 0, -1, 1L);
        createDispatchCase("room-high-sent", true, true, true, 0, 1, 0, 1L);
        createDispatchCase("room-high-private", true, true, true, -50, 1, -1, 1L);

        entityManager.flush();
        entityManager.clear();

        List<Long> partIds = liveMsgRepository.findPendingHighDispatchPartIds(PageRequest.of(0, 20));

        assertEquals(List.of(eligiblePartId), partIds);
    }

    private Long createDispatchCase(String roomId,
                                    boolean sendDm,
                                    boolean sendSc,
                                    boolean publish,
                                    int historyCode,
                                    int pool,
                                    int msgCode,
                                    Long cid) {
        RecordRoom room = new RecordRoom();
        room.setRoomId(roomId);
        room.setUname(roomId);
        room.setSendDm(sendDm);
        room.setSendSc(sendSc);
        entityManager.persist(room);

        RecordHistory history = new RecordHistory();
        history.setRoomId(roomId);
        history.setPublish(publish);
        history.setCode(historyCode);
        history.setSendReply(true);
        history.setEventId("history-" + UUID.randomUUID());
        entityManager.persist(history);

        RecordHistoryPart part = new RecordHistoryPart();
        part.setRoomId(roomId);
        part.setHistoryId(history.getId());
        part.setCid(cid);
        part.setUploadRetryCount(0);
        part.setEventId("part-" + UUID.randomUUID());
        part.setFilePath("part-" + UUID.randomUUID() + ".flv");
        entityManager.persist(part);

        LiveMsg msg = new LiveMsg();
        msg.setPartId(part.getId());
        msg.setBvid("BV" + UUID.randomUUID().toString().replace("-", ""));
        msg.setCid(cid);
        msg.setPool(pool);
        msg.setCode(msgCode);
        msg.setSendTime(1000L);
        msg.setContext("test");
        entityManager.persist(msg);

        return part.getId();
    }
}
