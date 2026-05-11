package top.sshh.bililiverecoder.repo;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RecordRoom;

import java.util.List;

@Repository
public interface RecordRoomRepository extends CrudRepository<RecordRoom, Long> {

    RecordRoom findByRoomId(String roomId);

    @Query("select r from RecordRoom r order by case when r.sortOrder is null then 1 else 0 end, r.sortOrder asc, r.id asc")
    List<RecordRoom> findAllOrderBySortOrder();

    @Query("select coalesce(max(r.sortOrder), 0) from RecordRoom r")
    Integer findMaxSortOrder();

    List<RecordRoom> findByUpload(boolean upload);

    List<RecordRoom> findBySendDmIsTrue();

    List<RecordRoom> findByDeleteType(int type);
}
