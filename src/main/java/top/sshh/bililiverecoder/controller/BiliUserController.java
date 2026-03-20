package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.jayway.jsonpath.JsonPath;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliSessionDto;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/biliUser")
public class BiliUserController {

    @Autowired
    BiliUserRepository biliUserRepository;

    // 存储登录会话信息：key -> {authCode, createTime, status, message}
    private final Map<String, LoginSession> loginSessionMap = new ConcurrentHashMap<>();
    
    // 定时清理过期会话（5分钟）
    private static final long SESSION_EXPIRE_MS = 5 * 60 * 1000;
    
    // 登录会话类
    private static class LoginSession {
        String authCode;
        long createTime;
        volatile String status; // "pending", "success", "failed", "expired"
        volatile String message;
        
        LoginSession(String authCode) {
            this.authCode = authCode;
            this.createTime = System.currentTimeMillis();
            this.status = "pending";
            this.message = "等待扫码";
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - createTime > SESSION_EXPIRE_MS;
        }
    }

    @GetMapping("/login")
    public Map<String, String> loginUser() throws Exception {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        
        // 清理过期会话
        cleanExpiredSessions();
        
        long apiCallStartNs = System.nanoTime();
        BiliApi.BiliResponseDto<BiliApi.GenerateQRDto> s = BiliApi.generateQRUrlTV();
        if (s.getCode() != 0) {
            log.warn("[BLR] {}", LogKvs.event("BiliUser.LoginQr.Generate.Failed")
                    .add("code", s.getCode())
                    .addIfNotBlank("msg", s.getMessage())
                    .addStageCostMs("apiCall", apiCallStartNs)
                    .addStageCostMs("total", totalStartNs));
            result.put("error", "生成二维码异常，请检查日志");
            return result;
        }

        long qrEncodeStartNs = System.nanoTime();
        BitMatrix bm = new QRCodeWriter().encode(s.getData().getUrl(),
                BarcodeFormat.QR_CODE, 256, 256);
        BufferedImage bi = MatrixToImageWriter.toBufferedImage(bm);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        ImageIO.write(bi, "jpg", stream);
        byte[] bytes = Base64.encodeBase64(stream.toByteArray());
        String imagesBase64 = new String(bytes);
        
        // 生成会话key并存储
        String sessionKey = imagesBase64.substring(imagesBase64.length() - 100);
        LoginSession session = new LoginSession(s.getData().getAuth_code());
        loginSessionMap.put(sessionKey, session);

        log.info("[BLR] {}", LogKvs.event("BiliUser.LoginQr.Generate.Success")
            .add("keyLen", sessionKey.length())
            .addStageCostMs("apiCall", apiCallStartNs)
            .addStageCostMs("qrEncode", qrEncodeStartNs)
            .addStageCostMs("total", totalStartNs));
        
        result.put("image", imagesBase64);
        result.put("key", sessionKey);
        return result;
    }

    @GetMapping("/loginCheck")
    public Map<String, String> loginCheck(@RequestParam String key) {
        long totalStartNs = System.nanoTime();
        Map<String, String> result = new HashMap<>();
        LoginSession session = loginSessionMap.get(key);
        
        if (session == null) {
            result.put("status", "failed");
            result.put("message", "会话不存在或已过期");
            return result;
        }
        
        // 检查是否过期
        if (session.isExpired()) {
            loginSessionMap.remove(key);
            result.put("status", "expired");
            result.put("message", "二维码已过期，请刷新");
            return result;
        }
        
        // 如果已经有结果，直接返回
        if (!"pending".equals(session.status)) {
            if ("success".equals(session.status)) {
                loginSessionMap.remove(key);
            }
            result.put("status", session.status);
            result.put("message", session.message);
            return result;
        }
        
        // 检查登录状态（非阻塞，立即返回）
        try {
            String loginResp = BiliApi.loginOnTV(session.authCode);
            Integer code = JsonPath.read(loginResp, "code");
            
            if (code == 0) {
                // 登录成功，处理用户信息
                BiliSessionDto dto = JSON.parseObject(loginResp).getObject("data", BiliSessionDto.class);
                BiliBiliUser biliUser = biliUserRepository.findByUid(dto.getMid());
                if (biliUser == null) {
                    biliUser = new BiliBiliUser();
                }
                JSONArray cookies = JSON.parseArray(JsonPath.read(loginResp, "data.cookie_info.cookies").toString());
                StringBuilder cookieString = new StringBuilder();
                for (Object object : cookies) {
                    JSONObject cookie = (JSONObject) object;
                    cookieString.append(cookie.get("name").toString());
                    cookieString.append(":");
                    cookieString.append(cookie.get("value").toString());
                    cookieString.append("; ");
                }
                biliUser.setCookies(cookieString.toString());
                biliUser.setUid(dto.getMid());
                biliUser.setAccessToken(dto.getAccessToken());
                biliUser.setRefreshToken(dto.getRefreshToken());
                biliUser.setLogin(true);
                biliUser.setUpdateTime(LocalDateTime.now());
                String userInfo = BiliApi.appMyInfo(biliUser);
                biliUser.setUname(JsonPath.read(userInfo, "data.uname"));
                try {
                    biliUser.setFace(JsonPath.read(userInfo, "data.face"));
                } catch (Exception e) {
                    session.message = "登录成功，但头像获取失败";
                }
                log.info("[BLR] {}", LogKvs.event("BiliUser.Login.Success")
                    .add("uid", biliUser.getUid())
                    .add("uname", biliUser.getUname())
                    .addStageCostMs("total", totalStartNs));
                biliUserRepository.save(biliUser);
                
                session.status = "success";
                session.message = "登录成功";
                result.put("status", "success");
                result.put("message", "登录成功");
                loginSessionMap.remove(key);
                
            } else if (code == 86038) {
                // 二维码过期
                session.status = "expired";
                session.message = "二维码已过期";
                result.put("status", "expired");
                result.put("message", "二维码已过期，请刷新");
                loginSessionMap.remove(key);
                
            } else if (code == 86039) {
                // 等待扫码
                result.put("status", "pending");
                result.put("message", "等待扫码");
                
            } else if (code == 86090) {
                // 已扫码，等待确认
                result.put("status", "scanned");
                result.put("message", "已扫码，请在手机上确认");
                
            } else {
                // 其他错误
                result.put("status", "pending");
                result.put("message", JsonPath.read(loginResp, "message"));
            }
            
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("BiliUser.LoginCheck.Error")
                    .add("keyLen", key == null ? 0 : key.length())
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName())
                    .addStageCostMs("total", totalStartNs), e);
            result.put("status", "pending");
            result.put("message", "检查中...");
        }
        
        return result;
    }
    
    @GetMapping("/loginCancel")
    public Map<String, String> loginCancel(@RequestParam String key) {
        Map<String, String> result = new HashMap<>();
        loginSessionMap.remove(key);
        result.put("status", "cancelled");
        result.put("message", "已取消");
        return result;
    }
    
    private void cleanExpiredSessions() {
        loginSessionMap.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    // 保留旧接口兼容性（已废弃，但保留避免报错）
    @GetMapping("loginReturn")
    @Deprecated
    public Map<String, String> loginReturn(@RequestParam String key) {
        Map<String, String> result = new HashMap<>();
        result.put("type", "warning");
        result.put("msg", "此接口已废弃，请刷新页面使用新版登录");
        return result;
    }

    @GetMapping("/list")
    public List<BiliBiliUser> listBillUser() {
        List<BiliBiliUser> list = new ArrayList<>();
        for (BiliBiliUser biliBiliUser : biliUserRepository.findAll()) {
            biliBiliUser.setAccessToken(null);
            biliBiliUser.setRefreshToken(null);
            list.add(biliBiliUser);
        }
        return list;
    }

    @PostMapping("/update")
    public boolean updateBillUser(@RequestBody BiliBiliUser user) {
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(user.getId());
        if (userOptional.isPresent()) {
            BiliBiliUser dbUser = userOptional.get();
            dbUser.setEnable(user.isEnable());
            dbUser.setUpdateTime(LocalDateTime.now());
            biliUserRepository.save(dbUser);
        }
        return false;
    }

    @GetMapping("/delete/{id}")
    public Map<String, String> delete(@PathVariable("id") Long id) {
        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入用户id");
            return result;
        }

        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(id);
        if (userOptional.isPresent()) {
            biliUserRepository.delete(userOptional.get());
            result.put("type", "success");
            result.put("msg", "用户删除成功");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "用户不存在");
            return result;
        }
    }

    @GetMapping("/refresh/{id}")
    public Map<String, Object> refresh(@PathVariable("id") Long id) {
        Map<String, Object> result = new HashMap<>();
        Optional<BiliBiliUser> userOptional = biliUserRepository.findById(id);
        if (userOptional.isPresent()) {
            BiliBiliUser user = userOptional.get();
            try {
                BiliApi.BiliUserCardResponseDto cardResp = BiliApi.getUserCard(user.getUid());
                if (cardResp != null && cardResp.getCode() == 0 && cardResp.getCard() != null) {
                    user.setUname(cardResp.getCard().getName());
                    user.setFace(cardResp.getCard().getFace());
                    user.setUpdateTime(LocalDateTime.now());
                    biliUserRepository.save(user);
                    result.put("success", true);
                    result.put("user", user);
                    result.put("msg", "用户信息已更新");
                } else {
                    result.put("success", false);
                    result.put("msg", "获取用户信息失败: " + (cardResp != null ? cardResp.getCode() : "未知错误"));
                }
            } catch (Exception e) {
                result.put("success", false);
                result.put("msg", "更新异常: " + e.getMessage());
            }
        } else {
            result.put("success", false);
            result.put("msg", "用户不存在");
        }
        return result;
    }
}
