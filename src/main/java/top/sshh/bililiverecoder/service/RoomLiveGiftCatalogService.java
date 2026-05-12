package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RoomLiveGiftCatalog;
import top.sshh.bililiverecoder.repo.RoomLiveGiftCatalogRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class RoomLiveGiftCatalogService {

    private static final long SYNC_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final long FAILED_SYNC_COOLDOWN_MS = 60L * 60L * 1000L;

    @Autowired
    private RoomLiveGiftCatalogRepository giftCatalogRepository;

    private final Map<String, Long> roomNextSyncAt = new ConcurrentHashMap<>();

    public int syncRoomGiftCatalog(String roomId, boolean force) {
        if (StringUtils.isBlank(roomId)) {
            return 0;
        }
        long nowMs = System.currentTimeMillis();
        Long nextSyncAt = roomNextSyncAt.get(roomId);
        if (!force && nextSyncAt != null && nowMs < nextSyncAt) {
            return 0;
        }
        try {
            String response = BiliApi.getLiveGiftConfig(roomId);
            JSONObject root = JSON.parseObject(response);
            if (root == null || root.getIntValue("code") != 0) {
                log.info("[BLR] {}", LogKvs.event("GiftCatalog.Sync.Skip")
                        .add("roomId", roomId)
                        .add("code", root == null ? null : root.getInteger("code"))
                        .addIfNotBlank("msg", root == null ? null : root.getString("message")));
                roomNextSyncAt.put(roomId, nowMs + FAILED_SYNC_COOLDOWN_MS);
                return 0;
            }
            JSONArray list = extractGiftList(root.getJSONObject("data"));
            if (list == null || list.isEmpty()) {
                roomNextSyncAt.put(roomId, nowMs + FAILED_SYNC_COOLDOWN_MS);
                return 0;
            }
            List<Integer> giftIds = new ArrayList<>();
            for (Object itemObject : list) {
                if (itemObject instanceof JSONObject item) {
                    Integer giftId = firstInteger(item, "id", "gift_id", "giftId");
                    if (giftId != null) {
                        giftIds.add(giftId);
                    }
                }
            }
            Map<Integer, RoomLiveGiftCatalog> existing = new HashMap<>();
            if (!giftIds.isEmpty()) {
                giftCatalogRepository.findByRoomIdAndGiftIdIn(roomId, giftIds).forEach(item -> {
                    if (item.getGiftId() != null) {
                        existing.put(item.getGiftId(), item);
                    }
                });
            }
            List<RoomLiveGiftCatalog> toSave = new ArrayList<>();
            LocalDateTime now = LocalDateTime.now();
            for (Object itemObject : list) {
                if (!(itemObject instanceof JSONObject item)) {
                    continue;
                }
                Integer giftId = firstInteger(item, "id", "gift_id", "giftId");
                if (giftId == null) {
                    continue;
                }
                Long priceCoin = firstLong(item, "price", "discount_price");
                String giftName = firstString(item, "name", "gift_name", "giftName");
                RoomLiveGiftCatalog catalog = existing.getOrDefault(giftId, new RoomLiveGiftCatalog());
                catalog.setRoomId(roomId);
                catalog.setGiftId(giftId);
                if (StringUtils.isNotBlank(giftName)) {
                    catalog.setGiftName(giftName);
                }
                if (priceCoin != null) {
                    catalog.setPriceCoin(priceCoin);
                    catalog.setPriceCny(toCny(priceCoin));
                }
                catalog.setUpdatedAt(now);
                toSave.add(catalog);
                existing.put(giftId, catalog);
            }
            giftCatalogRepository.saveAll(toSave);
            roomNextSyncAt.put(roomId, nowMs + SYNC_INTERVAL_MS);
            log.info("[BLR] {}", LogKvs.event("GiftCatalog.Sync.Done")
                    .add("roomId", roomId)
                    .add("count", toSave.size()));
            return toSave.size();
        } catch (Exception e) {
            roomNextSyncAt.put(roomId, System.currentTimeMillis() + FAILED_SYNC_COOLDOWN_MS);
            log.info("[BLR] {}", LogKvs.event("GiftCatalog.Sync.Failed")
                    .add("roomId", roomId)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
            return 0;
        }
    }

    public BigDecimal toCny(Long priceCoin) {
        if (priceCoin == null || priceCoin <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(priceCoin).divide(BigDecimal.valueOf(1000));
    }

    private JSONArray extractGiftList(JSONObject data) {
        if (data == null) {
            return null;
        }
        JSONArray direct = data.getJSONArray("list");
        if (direct != null) {
            return direct;
        }
        JSONObject giftConfig = data.getJSONObject("gift_config");
        JSONObject baseConfig = giftConfig == null ? null : giftConfig.getJSONObject("base_config");
        return baseConfig == null ? null : baseConfig.getJSONArray("list");
    }

    private Integer firstInteger(JSONObject json, String... keys) {
        Long value = firstLong(json, keys);
        return value == null ? null : value.intValue();
    }

    private Long firstLong(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null && StringUtils.isNumeric(value.toString())) {
                return Long.parseLong(value.toString());
            }
        }
        return null;
    }

    private String firstString(JSONObject json, String... keys) {
        for (String key : keys) {
            Object value = json.get(key);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
