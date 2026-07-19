package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.PartFileLocation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface PartFileLocationRepository extends CrudRepository<PartFileLocation, Long> {
    List<PartFileLocation> findByPartIdOrderByIdAsc(Long partId);
    List<PartFileLocation> findByStorageRootIdOrderByIdAsc(Long storageRootId);
    boolean existsByPartId(Long partId);
    List<PartFileLocation> findByPartIdIn(Collection<Long> partIds);
    Optional<PartFileLocation> findByPartIdAndStorageRootIdAndRelativePath(Long partId, Long storageRootId, String relativePath);
    List<PartFileLocation> findByPartIdAndState(Long partId, PartFileLocation.LocationState state);
    List<PartFileLocation> findTop20ByStorageRootIdAndStateOrderByIdAsc(
            Long storageRootId, PartFileLocation.LocationState state);
    long countByPartIdAndRoleAndState(Long partId, PartFileLocation.LocationRole role, PartFileLocation.LocationState state);
    void deleteByPartId(Long partId);
    @Query("select l from PartFileLocation l order by l.id")
    @Transactional(readOnly = true)
    Stream<PartFileLocation> streamAll();
    @Query("select l from PartFileLocation l where l.state <> ?1 order by l.id")
    @Transactional(readOnly = true)
    Stream<PartFileLocation> streamCompleted(PartFileLocation.LocationState excludedState);
}
