package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.MultipartUploadPart;

import java.util.List;
import java.util.Optional;

@Repository
public interface MultipartUploadPartRepository extends CrudRepository<MultipartUploadPart, Long> {

    Optional<MultipartUploadPart> findBySessionIdAndPartNumber(Long sessionId, Integer partNumber);

    List<MultipartUploadPart> findBySessionIdAndStatus(Long sessionId, String status);

    List<MultipartUploadPart> findBySessionId(Long sessionId);

    long countBySessionIdAndStatus(Long sessionId, String status);

    @Transactional
    void deleteBySessionId(Long sessionId);
}
