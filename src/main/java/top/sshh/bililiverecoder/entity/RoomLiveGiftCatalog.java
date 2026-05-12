package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_gift_catalog",
        indexes = {
                @Index(name = "idx_room_live_gift_catalog_room_gift", columnList = "roomId,giftId")
        })
public class RoomLiveGiftCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String roomId;
    private Integer giftId;
    private String giftName;
    private Long priceCoin;
    private BigDecimal priceCny;
    private LocalDateTime updatedAt;
}
