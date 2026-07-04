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
    private static final long[] FAILED_SYNC_COOLDOWN_STEPS_MS = {
            5L * 60L * 1000L,
            15L * 60L * 1000L,
            60L * 60L * 1000L
    };

    @Autowired
    private RoomLiveGiftCatalogRepository giftCatalogRepository;

    private final Map<String, Long> roomNextSuccessSyncAt = new ConcurrentHashMap<>();
    private final Map<String, FailureState> roomFailureState = new ConcurrentHashMap<>();

    public GiftCatalogSyncResult syncRoomGiftCatalog(String roomId, boolean force) {
        if (StringUtils.isBlank(roomId)) {
            return GiftCatalogSyncResult.skipped("invalid-room", "roomId为空", 0);
        }
        long nowMs = System.currentTimeMillis();
        Long nextSuccessSyncAt = roomNextSuccessSyncAt.get(roomId);
        if (!force && nextSuccessSyncAt != null && nowMs < nextSuccessSyncAt) {
            return GiftCatalogSyncResult.skipped("success-cooldown", "礼物价格目录仍在成功同步冷却期", 0);
        }
        FailureState failureState = roomFailureState.get(roomId);
        if (failureState != null && nowMs < failureState.nextRetryAt()) {
            return GiftCatalogSyncResult.skipped("failure-cooldown", "礼物价格目录同步刚失败过，暂时使用本地缓存", 0);
        }
        try {
            String response = BiliApi.getLiveGiftConfig(roomId);
            JSONObject root = JSON.parseObject(response);
            if (root == null || root.getIntValue("code") != 0) {
                GiftCatalogSyncResult result = failedResult(roomId, nowMs,
                        "接口返回异常: code=" + (root == null ? "null" : root.getInteger("code"))
                                + ", msg=" + (root == null ? "" : root.getString("message")));
                log.info("[BLR] {}", LogKvs.event("GiftCatalog.Sync.Skip")
                        .add("roomId", roomId)
                        .add("code", root == null ? null : root.getInteger("code"))
                        .addIfNotBlank("msg", root == null ? null : root.getString("message")));
                return result;
            }
            JSONArray list = extractGiftList(root.getJSONObject("data"));
            if (list == null || list.isEmpty()) {
                return failedResult(roomId, nowMs, "接口返回的礼物列表为空");
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
            roomFailureState.remove(roomId);
            roomNextSuccessSyncAt.put(roomId, nowMs + SYNC_INTERVAL_MS);
            log.debug("[BLR] {}", LogKvs.event("GiftCatalog.Sync.Done")
                    .add("roomId", roomId)
                    .add("count", toSave.size()));
            return GiftCatalogSyncResult.success("api", "礼物价格目录同步成功", toSave.size());
        } catch (Exception e) {
            GiftCatalogSyncResult result = failedResult(roomId, System.currentTimeMillis(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            log.info("[BLR] {}", LogKvs.event("GiftCatalog.Sync.Failed")
                    .add("roomId", roomId)
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()));
            return result;
        }
    }

    private GiftCatalogSyncResult failedResult(String roomId, long nowMs, String message) {
        FailureState previous = roomFailureState.get(roomId);
        int nextFailures = previous == null ? 1 : previous.failures() + 1;
        long cooldownMs = FAILED_SYNC_COOLDOWN_STEPS_MS[Math.min(nextFailures - 1, FAILED_SYNC_COOLDOWN_STEPS_MS.length - 1)];
        roomFailureState.put(roomId, new FailureState(nextFailures, nowMs + cooldownMs));
        return GiftCatalogSyncResult.failed("api", message, 0);
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

    private record FailureState(int failures, long nextRetryAt) {
    }

    public record GiftCatalogSyncResult(boolean success,
                                        boolean skipped,
                                        boolean failed,
                                        String source,
                                        String message,
                                        int syncedCount) {

        static GiftCatalogSyncResult success(String source, String message, int syncedCount) {
            return new GiftCatalogSyncResult(true, false, false, source, message, syncedCount);
        }

        static GiftCatalogSyncResult skipped(String source, String message, int syncedCount) {
            return new GiftCatalogSyncResult(false, true, false, source, message, syncedCount);
        }

        static GiftCatalogSyncResult failed(String source, String message, int syncedCount) {
            return new GiftCatalogSyncResult(false, false, true, source, message, syncedCount);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("success", success);
            map.put("skipped", skipped);
            map.put("failed", failed);
            map.put("source", source);
            map.put("message", message);
            map.put("syncedCount", syncedCount);
            return map;
        }
    }
}
