package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.StorageRoot;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface StorageRootRepository extends CrudRepository<StorageRoot, Long> {
    Optional<StorageRoot> findByRootKey(String rootKey);
    List<StorageRoot> findByRootTypeAndActiveForNewFilesIsTrue(StorageRoot.RootType rootType);
    List<StorageRoot> findAllByOrderByIdAsc();
    @Query("select r from StorageRoot r order by r.id")
    @Transactional(readOnly = true)
    Stream<StorageRoot> streamAll();
}
