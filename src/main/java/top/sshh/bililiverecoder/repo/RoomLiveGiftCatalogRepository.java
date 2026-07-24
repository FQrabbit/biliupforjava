package top.sshh.bililiverecoder.repo;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import top.sshh.bililiverecoder.entity.RoomLiveGiftCatalog;

import java.util.List;

@Repository
public interface RoomLiveGiftCatalogRepository extends CrudRepository<RoomLiveGiftCatalog, Long> {

    RoomLiveGiftCatalog findByRoomIdAndGiftId(String roomId, Integer giftId);

    List<RoomLiveGiftCatalog> findByRoomIdAndGiftIdIn(String roomId, List<Integer> giftIds);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query("delete from RoomLiveGiftCatalog c where c.roomId = ?1")
    int deleteByRoomId(String roomId);

    @org.springframework.data.jpa.repository.Query("""
            select c from RoomLiveGiftCatalog c
            where c.giftId in ?1
              and c.priceCoin is not null
              and c.priceCoin > 0
            order by c.updatedAt desc
            """)
    List<RoomLiveGiftCatalog> findPricedByGiftIdIn(List<Integer> giftIds);

    @org.springframework.data.jpa.repository.Query("""
            select c from RoomLiveGiftCatalog c
            where c.giftName in ?1
              and c.priceCoin is not null
              and c.priceCoin > 0
            order by c.updatedAt desc
            """)
    List<RoomLiveGiftCatalog> findPricedByGiftNameIn(List<String> giftNames);
}
