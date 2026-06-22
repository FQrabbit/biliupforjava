package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RecordRoom;
import top.sshh.bililiverecoder.entity.RoomLiveEvent;
import top.sshh.bililiverecoder.entity.RoomLiveGiftCatalog;
import top.sshh.bililiverecoder.repo.RoomLiveGiftCatalogRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GiftReplyCandidateService {

    @Autowired
    private RoomLiveGiftCatalogRepository giftCatalogRepository;

    @Autowired
    private RoomLiveGiftCatalogService giftCatalogService;

    public GiftReplyScan scan(RecordHistory history,
                              RecordRoom room,
                              List<RecordHistoryPart> parts,
                              List<RoomLiveEvent> giftEvents) {
        BigDecimal minPrice = normalizeMinPrice(room == null ? null : room.getGiftReplyMinPriceCny());
        if (giftEvents == null || giftEvents.isEmpty()) {
            return new GiftReplyScan(minPrice, List.of(), 0, 0, 0, Map.of());
        }

        Set<Long> partIds = parts == null ? Set.of() : parts.stream()
                .filter(Objects::nonNull)
                .map(RecordHistoryPart::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        GiftCatalogLookup lookup = loadGiftCatalog(history, room, giftEvents);
        int skippedNoPart = 0;
        int skippedNoPrice = 0;
        int skippedBelowThreshold = 0;
        Map<String, Integer> matchedByPriceSource = new HashMap<>();
        java.util.ArrayList<GiftReplyCandidate> candidates = new java.util.ArrayList<>();
        for (RoomLiveEvent event : giftEvents) {
            if (event == null || event.getPartId() == null || !partIds.contains(event.getPartId())) {
                skippedNoPart++;
                continue;
            }
            GiftPriceResolution unitPrice = resolveGiftUnitPriceCny(event, lookup);
            if (unitPrice.price() == null) {
                skippedNoPrice++;
                continue;
            }
            if (unitPrice.price().compareTo(minPrice) < 0) {
                skippedBelowThreshold++;
                continue;
            }
            long count = event.getGiftCount() == null || event.getGiftCount() <= 0 ? 1L : event.getGiftCount();
            BigDecimal totalPrice = resolveGiftTotalPriceCny(event, unitPrice.price(), count);
            candidates.add(new GiftReplyCandidate(event, unitPrice.price(), totalPrice, count,
                    unitPrice.giftName(), unitPrice.source()));
            matchedByPriceSource.merge(unitPrice.source(), 1, Integer::sum);
        }
        return new GiftReplyScan(minPrice, candidates, skippedNoPart, skippedNoPrice,
                skippedBelowThreshold, matchedByPriceSource);
    }

    public String formatMoney(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private BigDecimal normalizeMinPrice(BigDecimal minPrice) {
        if (minPrice == null || minPrice.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return minPrice;
    }

    private GiftCatalogLookup loadGiftCatalog(RecordHistory history, RecordRoom room, List<RoomLiveEvent> giftEvents) {
        String roomId = StringUtils.isNotBlank(history == null ? null : history.getRoomId())
                ? history.getRoomId()
                : (room == null ? null : room.getRoomId());
        List<Integer> giftIds = giftEvents.stream()
                .map(RoomLiveEvent::getGiftId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, RoomLiveGiftCatalog> roomCatalogByGiftId = new HashMap<>();
        if (StringUtils.isNotBlank(roomId) && !giftIds.isEmpty()) {
            for (RoomLiveGiftCatalog catalog : giftCatalogRepository.findByRoomIdAndGiftIdIn(roomId, giftIds)) {
                if (catalog.getGiftId() != null) {
                    roomCatalogByGiftId.put(giftCatalogKey(roomId, catalog.getGiftId()), catalog);
                }
            }
        }

        Map<Integer, RoomLiveGiftCatalog> localByGiftId = new HashMap<>();
        if (!giftIds.isEmpty()) {
            for (RoomLiveGiftCatalog catalog : giftCatalogRepository.findPricedByGiftIdIn(giftIds)) {
                if (catalog.getGiftId() != null && catalog.getPriceCoin() != null && catalog.getPriceCoin() > 0) {
                    localByGiftId.putIfAbsent(catalog.getGiftId(), catalog);
                }
            }
        }

        List<String> giftNames = giftEvents.stream()
                .map(RoomLiveEvent::getGiftName)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();
        Map<String, RoomLiveGiftCatalog> localByGiftName = new HashMap<>();
        if (!giftNames.isEmpty()) {
            for (RoomLiveGiftCatalog catalog : giftCatalogRepository.findPricedByGiftNameIn(giftNames)) {
                if (StringUtils.isNotBlank(catalog.getGiftName())
                        && catalog.getPriceCoin() != null
                        && catalog.getPriceCoin() > 0) {
                    localByGiftName.putIfAbsent(catalog.getGiftName(), catalog);
                }
            }
        }

        return new GiftCatalogLookup(roomId, roomCatalogByGiftId, localByGiftId, localByGiftName);
    }

    private GiftPriceResolution resolveGiftUnitPriceCny(RoomLiveEvent event, GiftCatalogLookup lookup) {
        Long unitCoin = event.getGiftPriceCoin();
        long count = event.getGiftCount() == null || event.getGiftCount() <= 0 ? 1L : event.getGiftCount();
        if ((unitCoin == null || unitCoin <= 0) && event.getGiftTotalCoin() != null && event.getGiftTotalCoin() > 0) {
            unitCoin = event.getGiftTotalCoin() / count;
        }
        if (unitCoin != null && unitCoin > 0) {
            return new GiftPriceResolution(giftCatalogService.toCny(unitCoin), event.getGiftName(), "raw");
        }

        RoomLiveGiftCatalog catalog = null;
        if (event.getGiftId() != null && lookup != null && StringUtils.isNotBlank(lookup.roomId())) {
            catalog = lookup.roomCatalogByGiftId().get(giftCatalogKey(lookup.roomId(), event.getGiftId()));
            BigDecimal price = catalogPriceCny(catalog);
            if (price != null) {
                return new GiftPriceResolution(price, catalog.getGiftName(), "room_catalog");
            }
        }

        if (event.getGiftId() != null && lookup != null) {
            catalog = lookup.localByGiftId().get(event.getGiftId());
            BigDecimal price = catalogPriceCny(catalog);
            if (price != null) {
                return new GiftPriceResolution(price, catalog.getGiftName(), "local_gift_id");
            }
        }

        if (StringUtils.isNotBlank(event.getGiftName()) && lookup != null) {
            catalog = lookup.localByGiftName().get(event.getGiftName());
            BigDecimal price = catalogPriceCny(catalog);
            if (price != null) {
                return new GiftPriceResolution(price, catalog.getGiftName(), "local_gift_name");
            }
        }
        return new GiftPriceResolution(null, event.getGiftName(), "missing");
    }

    private BigDecimal resolveGiftTotalPriceCny(RoomLiveEvent event, BigDecimal unitPrice, long count) {
        if (event.getGiftTotalCoin() != null && event.getGiftTotalCoin() > 0) {
            return giftCatalogService.toCny(event.getGiftTotalCoin());
        }
        return unitPrice.multiply(BigDecimal.valueOf(count));
    }

    private BigDecimal catalogPriceCny(RoomLiveGiftCatalog catalog) {
        if (catalog == null) {
            return null;
        }
        if (catalog.getPriceCny() != null && catalog.getPriceCny().compareTo(BigDecimal.ZERO) > 0) {
            return catalog.getPriceCny();
        }
        if (catalog.getPriceCoin() != null && catalog.getPriceCoin() > 0) {
            return giftCatalogService.toCny(catalog.getPriceCoin());
        }
        return null;
    }

    private String giftCatalogKey(String roomId, Integer giftId) {
        return roomId + "#" + giftId;
    }

    private record GiftCatalogLookup(String roomId,
                                     Map<String, RoomLiveGiftCatalog> roomCatalogByGiftId,
                                     Map<Integer, RoomLiveGiftCatalog> localByGiftId,
                                     Map<String, RoomLiveGiftCatalog> localByGiftName) {
    }

    private record GiftPriceResolution(BigDecimal price, String giftName, String source) {
    }

    public record GiftReplyScan(BigDecimal minPrice,
                                List<GiftReplyCandidate> candidates,
                                int skippedNoPart,
                                int skippedNoPrice,
                                int skippedBelowThreshold,
                                Map<String, Integer> matchedByPriceSource) {
    }

    public record GiftReplyCandidate(RoomLiveEvent event,
                                     BigDecimal unitPrice,
                                     BigDecimal totalPrice,
                                     long count,
                                     String giftName,
                                     String priceSource) {
    }
}
