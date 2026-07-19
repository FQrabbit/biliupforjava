package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.PartFileOperation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PartFileOperationRepository extends CrudRepository<PartFileOperation, Long> {
    Optional<PartFileOperation> findByOperationKey(String operationKey);
    Optional<PartFileOperation> findFirstByPartIdAndStatusInOrderByCreatedAtDesc(
            Long partId, Collection<PartFileOperation.OperationStatus> statuses);
    List<PartFileOperation> findByStatusInOrderByCreatedAtAsc(Collection<PartFileOperation.OperationStatus> statuses);
    Optional<PartFileOperation> findFirstByPartIdOrderByCreatedAtDesc(Long partId);
    Optional<PartFileOperation> findFirstByPartIdAndOperationTypeOrderByCreatedAtDesc(
            Long partId, PartFileOperation.OperationType operationType);
    void deleteByPartId(Long partId);
}
