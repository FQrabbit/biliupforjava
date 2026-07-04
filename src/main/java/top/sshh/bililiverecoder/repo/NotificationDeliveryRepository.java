package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.NotificationDelivery;

import java.util.List;

@Repository
public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    List<NotificationDelivery> findTop50ByOrderByCreateTimeDesc();
}
