package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RoomLiveGiftCatalog;

import java.util.List;

@Repository
public interface RoomLiveGiftCatalogRepository extends CrudRepository<RoomLiveGiftCatalog, Long> {

    RoomLiveGiftCatalog findByRoomIdAndGiftId(String roomId, Integer giftId);

    List<RoomLiveGiftCatalog> findByRoomIdAndGiftIdIn(String roomId, List<Integer> giftIds);
}
