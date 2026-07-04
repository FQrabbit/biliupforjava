package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.NotificationRule;

import java.util.List;

@Repository
public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {

    List<NotificationRule> findByEventType(String eventType);

    List<NotificationRule> findByEventTypeAndEnabledTrue(String eventType);

    void deleteByEventTypeAndRoomId(String eventType, String roomId);
}
