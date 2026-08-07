package top.sshh.bililiverecoder.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.NotificationChannel;

import java.util.List;

@Repository
public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {

    List<NotificationChannel> findByEnabledTrue();

    List<NotificationChannel> findByIdGreaterThanOrderByIdAsc(Long id, Pageable pageable);
}
