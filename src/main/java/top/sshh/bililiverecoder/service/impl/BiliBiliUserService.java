package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliUserCard;
import top.sshh.bililiverecoder.entity.data.BiliSessionDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class BiliBiliUserService {


    @Autowired
    private BiliUserRepository userRepository;

    private static final int PROFILE_BATCH_SIZE = 50;
    private static final long PROFILE_CACHE_HOURS = 24;

    public boolean refreshToken(BiliBiliUser user) {
        String response = BiliApi.refreshToken(user);

        Integer code = JsonPath.read(response, "code");
        if (code == 0){
            BiliSessionDto dto = JSON.parseObject(JSON.toJSONString(JsonPath.read(response, "data.token_info")),BiliSessionDto.class);
            JSONArray cookies = JSON.parseArray(JsonPath.read(response, "data.cookie_info.cookies").toString());
            StringBuilder cookieString = new StringBuilder();
            for (Object object : cookies) {
                JSONObject cookie = (JSONObject)object;
                cookieString.append(cookie.get("name").toString());
                cookieString.append(":");
                cookieString.append(cookie.get("value").toString());
                cookieString.append("; ");
            }

            user.setCookies(cookieString.toString());
            log.info("[BLR] {}", LogKvs.event("User.RefreshToken.Success")
                    .add("uname", user.getUname()));
            user.setUid(dto.getMid());
            user.setAccessToken(dto.getAccessToken());
            user.setRefreshToken(dto.getRefreshToken());
            try{
                String userInfo = BiliApi.appMyInfo(user);
                user.setUname(JsonPath.read(userInfo, "data.uname"));
                try {
                    user.setFace(JsonPath.read(userInfo, "data.face"));
                } catch (Exception e) {}
            }catch (Exception e){
                log.warn("[BLR] {}", LogKvs.event("User.RefreshToken.MyInfoFailed")
                        .add("uname", user.getUname())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }
            user.setLogin(true);
            user.setUpdateTime(LocalDateTime.now());
            userRepository.save(user);
            return true;
        }else {
            try {
                String userInfo = BiliApi.appMyInfo(user);
                user.setUname(JsonPath.read(userInfo, "data.uname"));
                try {
                    user.setFace(JsonPath.read(userInfo, "data.face"));
                } catch (Exception e) {}
                user.setLogin(true);
                user.setUpdateTime(LocalDateTime.now());
                userRepository.save(user);
                log.warn("[BLR] {}", LogKvs.event("User.RefreshToken.FailedButUsable")
                        .add("uname", user.getUname())
                        .add("code", code)
                        .add("response", response));
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("User.RefreshToken.FailedAndMyInfoFailed")
                        .add("uname", user.getUname())
                        .add("code", code)
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
            }
            user.setLogin(false);
            user.setEnable(false);
            user.setUpdateTime(LocalDateTime.now());
            userRepository.save(user);
            log.error("[BLR] {}", LogKvs.event("User.RefreshToken.Failed")
                    .add("uname", user.getUname())
                    .add("code", code)
                    .add("response", response));
            return false;
        }

    }

    public List<BiliBiliUser> refreshCachedProfilesIfNeeded(Collection<BiliBiliUser> users, boolean force) {
        List<BiliBiliUser> list = new ArrayList<>();
        if (users == null || users.isEmpty()) {
            return list;
        }
        users.forEach(list::add);

        List<BiliBiliUser> pending = list.stream()
                .filter(user -> user != null && user.getUid() != null)
                .filter(user -> force || StringUtils.isBlank(user.getFace()) || isProfileCacheExpired(user))
                .toList();
        if (pending.isEmpty()) {
            return list;
        }

        List<BiliBiliUser> changed = new ArrayList<>();
        for (int from = 0; from < pending.size(); from += PROFILE_BATCH_SIZE) {
            List<BiliBiliUser> batch = pending.subList(from, Math.min(from + PROFILE_BATCH_SIZE, pending.size()));
            try {
                Map<Long, BiliUserCard> cards = BiliApi.getUserCards(
                        batch.stream().map(BiliBiliUser::getUid).toList(),
                        findUsableCookie(batch));
                for (BiliBiliUser user : batch) {
                    BiliUserCard card = cards.get(user.getUid());
                    if (card == null) {
                        continue;
                    }
                    boolean updated = false;
                    if (StringUtils.isNotBlank(card.getName()) && !card.getName().equals(user.getUname())) {
                        user.setUname(card.getName());
                        updated = true;
                    }
                    if (StringUtils.isNotBlank(card.getFace()) && !card.getFace().equals(user.getFace())) {
                        user.setFace(card.getFace());
                        updated = true;
                    }
                    if (updated || force || StringUtils.isBlank(user.getFace())) {
                        user.setUpdateTime(LocalDateTime.now());
                        changed.add(user);
                    }
                }
            } catch (Exception e) {
                log.warn("[BLR] {}", LogKvs.event("User.Profile.BatchRefreshFailed")
                        .add("batchSize", batch.size())
                        .addIfNotBlank("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()));
            }
        }
        if (!changed.isEmpty()) {
            userRepository.saveAll(changed);
        }
        return list;
    }

    public boolean refreshCachedProfile(BiliBiliUser user) {
        if (user == null || user.getUid() == null) {
            return false;
        }
        refreshCachedProfilesIfNeeded(List.of(user), true);
        return true;
    }

    private boolean isProfileCacheExpired(BiliBiliUser user) {
        LocalDateTime updatedAt = user.getUpdateTime();
        return updatedAt == null || updatedAt.isBefore(LocalDateTime.now().minusHours(PROFILE_CACHE_HOURS));
    }

    private String findUsableCookie(List<BiliBiliUser> users) {
        if (users == null) {
            return null;
        }
        return users.stream()
                .map(BiliBiliUser::getCookies)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }
}
