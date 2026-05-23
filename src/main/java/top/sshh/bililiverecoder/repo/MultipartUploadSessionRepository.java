package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.MultipartUploadSession;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MultipartUploadSessionRepository extends CrudRepository<MultipartUploadSession, Long> {

    Optional<MultipartUploadSession> findFirstByPartIdAndStatusInOrderByUpdatedAtDesc(Long partId, Collection<String> statuses);

    List<MultipartUploadSession> findByPartId(Long partId);

    List<MultipartUploadSession> findByHistoryId(Long historyId);
}
