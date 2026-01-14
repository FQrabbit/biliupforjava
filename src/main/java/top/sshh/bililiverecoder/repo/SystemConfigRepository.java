package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.SystemConfig;

@Repository
public interface SystemConfigRepository extends JpaRepository<SystemConfig, String> {
}
