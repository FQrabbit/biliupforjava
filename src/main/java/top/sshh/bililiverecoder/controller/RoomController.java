package top.sshh.bililiverecoder.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;
import top.sshh.bililiverecoder.util.UploadEnums;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/room")
@Slf4j
public class RoomController {
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    private BiliUserRepository userRepository;

    @Autowired
    private RecordHistoryRepository historyRepository;

    @Autowired
    private RecordHistoryPartRepository partRepository;


    @PostMapping
    public List<RecordRoom> list() {
        Iterator<RecordRoom> roomIterator = roomRepository.findAll().iterator();
        List<RecordRoom> list = new ArrayList<>();
        roomIterator.forEachRemaining(list::add);
        return list;
    }


    @PostMapping("/exportConfig")
    public void exportConfig(@RequestBody ExportConfigParams params, HttpServletResponse response) throws IOException {
        Map<String,Object> map = new HashMap<>();
        if(params.isExportRoom()){
            List<RecordRoom> roomList = this.list();
            map.put("roomList",roomList);
        }
        if(params.isExportUser()){
            List<BiliBiliUser> userList = new ArrayList<>();
            Iterator<BiliBiliUser> userIterator = userRepository.findAll().iterator();
            userIterator.forEachRemaining(userList::add);
            map.put("userList",userList);
        }
        if(params.isExportHistory()){
            List<RecordHistory> historyList = new ArrayList<>();
            Iterator<RecordHistory> historyIterator = historyRepository.findAll().iterator();
            historyIterator.forEachRemaining(historyList::add);
            map.put("historyList",historyList);
            List<RecordHistoryPart> partList = new ArrayList<>();
            Iterator<RecordHistoryPart> partIterator = partRepository.findAll().iterator();
            partIterator.forEachRemaining(partList::add);
            map.put("partList",partList);
        }
        String jsonString = JSON.toJSONString(map);
        String timeString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日HH点mm分"));
        // 构造响应头，指定文件名，并将文件名进行URL编码
        String encodedFilename = URLEncoder.encode("biliupForJavaConfig_"+timeString+".json", StandardCharsets.UTF_8).replaceAll("\\+", "%20");

        response.setContentType("application/json");
        response.setHeader("Content-Disposition", "attachment; filename="+encodedFilename);
        // 将JSON字符串写入到响应输出流中
        OutputStream out = response.getOutputStream();
        out.write(jsonString.getBytes());
        out.flush();
        out.close();
    }

    @PostMapping("/uploadConfig")
    public void uploadConfig(@RequestParam("file") MultipartFile file) throws IOException {
        // 获取上传的文件内容
        byte[] bytes = file.getBytes();
        // 将文件内容转换为JSON字符串
        String json = new String(bytes);

        // 将JSON字符串转换为Map对象
        Map<String,Object> configMap = JSON.parseObject(json, new TypeReference<>() {
        });
        List<RecordRoom> roomList = JSON.parseObject(JSON.toJSONString(configMap.get("roomList")), new TypeReference<>() {});
        List<BiliBiliUser> userList = JSON.parseObject(JSON.toJSONString(configMap.get("userList")), new TypeReference<>() {});
        List<RecordHistory> historyList = JSON.parseObject(JSON.toJSONString(configMap.get("historyList")), new TypeReference<>() {});
        List<RecordHistoryPart> partList = JSON.parseObject(JSON.toJSONString(configMap.get("partList")), new TypeReference<>() {});


        Map<Long,Long> userIdConverMap = new HashMap<>();
        if(userList != null && userList.size()>0){
            for (BiliBiliUser user : userList) {
                Long id = user.getId();
                user.setId(null);
                BiliBiliUser dbUser = userRepository.findByUid(user.getUid());
                if(dbUser != null){
                    user.setId(dbUser.getId());
                }
                userRepository.save(user);
                userIdConverMap.put(id,user.getId());
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Users.Success")
                    .add("count", userList.size()));
        }
        if(roomList != null && roomList.size()>0){
            for (RecordRoom room : roomList) {
                room.setId(null);
                room.setUploadUserId(userIdConverMap.get(room.getUploadUserId()));
                RecordRoom dbRoom = roomRepository.findByRoomId(room.getRoomId());
                if(dbRoom != null){
                    room.setId(dbRoom.getId());
                }
                roomRepository.save(room);
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Rooms.Success")
                    .add("count", roomList.size()));
        }
        Map<Long,Long> historyIdConverMap = new HashMap<>();
        if(historyList != null && historyList.size()>0){
            for (RecordHistory history : historyList) {
                Long oldId = history.getId();
                history.setId(null);
                RecordHistory dbHistory = historyRepository.findBySessionId(history.getSessionId());
                if(dbHistory != null){
                    history.setId(dbHistory.getId());
                }
                historyRepository.save(history);
                historyIdConverMap.put(oldId,history.getId());
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Histories.Success")
                    .add("count", historyList.size()));
        }
        if(partList != null && partList.size()>0){
            for (RecordHistoryPart part : partList) {
                part.setId(null);
                RecordHistoryPart dbPart = partRepository.findByFilePath(part.getFilePath());
                if(dbPart != null){
                    part.setId(dbPart.getId());
                }
                part.setHistoryId(historyIdConverMap.get(part.getHistoryId()));
                partRepository.save(part);
            }
            log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Parts.Success")
                    .add("count", partList.size()));
        }
        // 在控制台输出转换后的Map对象
        log.info("[BLR] {}", LogKvs.event("RoomConfig.Import.Done"));
    }

    @PostMapping("/update")
    public boolean update(@RequestBody RecordRoom room) {
        Optional<RecordRoom> roomOptional = roomRepository.findById(room.getId());
        if (roomOptional.isPresent()) {
            RecordRoom dbRoom = roomOptional.get();
            dbRoom.setTid(room.getTid());
            dbRoom.setTags(room.getTags());
            dbRoom.setUpload(room.isUpload());
            dbRoom.setUploadUserId(room.getUploadUserId());
            dbRoom.setSeasonId(room.getSeasonId());
            dbRoom.setSectionId(room.getSectionId());
            dbRoom.setHighEnergyCut(room.isHighEnergyCut());
            dbRoom.setPercentileRank(room.getPercentileRank());
            dbRoom.setIsOnlySelf(room.getIsOnlySelf());
            dbRoom.setNoDisturbance(room.getNoDisturbance());
            dbRoom.setTitleTemplate(room.getTitleTemplate());
            dbRoom.setPartTitleTemplate(room.getPartTitleTemplate());
            dbRoom.setDescTemplate(room.getDescTemplate());
            dbRoom.setDynamicTemplate(room.getDynamicTemplate());
            dbRoom.setCopyright(room.getCopyright());
            dbRoom.setLine(room.getLine());
            dbRoom.setCoverUrl(room.getCoverUrl());
            dbRoom.setWxuid(room.getWxuid());
            dbRoom.setPushMsgTags(room.getPushMsgTags());
            dbRoom.setFileSizeLimit(room.getFileSizeLimit());
            dbRoom.setDurationLimit(room.getDurationLimit());
            dbRoom.setDeleteType(room.getDeleteType());
            dbRoom.setDeleteDay(room.getDeleteDay());
            dbRoom.setMoveDir(room.getMoveDir());
            dbRoom.setSendDm(room.getSendDm());
            dbRoom.setSendSc(room.getSendSc());
            roomRepository.save(dbRoom);
            return true;
        }
        return false;
    }

    @PostMapping("/editLiveMsgSetting")
    public boolean editLiveMsgSetting(@RequestBody RecordRoom room) {
        Optional<RecordRoom> roomOptional = roomRepository.findById(room.getId());
        if (roomOptional.isPresent()) {
            RecordRoom dbRoom = roomOptional.get();
            dbRoom.setDmDistinct(room.getDmDistinct());
            dbRoom.setDmFanMedal(room.getDmFanMedal());
            dbRoom.setDmUlLevel(room.getDmUlLevel());
            dbRoom.setDmKeywordBlacklist(room.getDmKeywordBlacklist());
            roomRepository.save(dbRoom);
            return true;
        }
        return false;
    }

    @PostMapping("/add")
    public Map<String, String> add(@RequestBody RecordRoom add) {
        Map<String, String> result = new HashMap<>();
        if (StringUtils.isBlank(add.getRoomId())) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        RecordRoom room = roomRepository.findByRoomId(add.getRoomId());
        if (room != null) {
            result.put("type", "warning");
            result.put("msg", "房间号已存在");
            return result;
        } else {
            room = new RecordRoom();
            room.setRoomId(add.getRoomId());
            roomRepository.save(room);
            result.put("type", "success");
            result.put("msg", "添加成功");
            return result;
        }
    }

    @GetMapping("/delete/{roomId}")
    public Map<String, String> delete(@PathVariable("roomId") Long roomId) {
        Map<String, String> result = new HashMap<>();
        if (roomId == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }

        try {
            Optional<RecordRoom> roomOptional = roomRepository.findById(roomId);
            if (roomOptional.isPresent()) {
                roomRepository.delete(roomOptional.get());
                result.put("type", "success");
                result.put("msg", "房间删除成功");
                return result;
            } else {
                result.put("type", "warning");
                result.put("msg", "房间不存在");
                return result;
            }
        } catch (Exception e) {
            result.put("type", "error");
            result.put("msg", "房间删除失败==>" + e.getMessage());
            return result;
        }
    }

    @PostMapping("/uploadCover")
    public Map<String, String> uploadCover(@RequestParam Long id, @RequestParam("file") MultipartFile file) {

        Map<String, String> result = new HashMap<>();
        if (id == null) {
            result.put("type", "info");
            result.put("msg", "请输入房间号");
            return result;
        }
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                result.put("type", "warning");
                result.put("msg", "请上传图片文件");
                return result;
            }
            if (image.getWidth() < 1146 || image.getHeight() < 717) {
                result.put("type", "warning");
                result.put("msg", "上传图片分辨率不低于1146*717,当前分辨率为"+image.getWidth()+"*"+image.getHeight());
                return result;
            }
        } catch (IOException e) {
            result.put("type", "warning");
            result.put("msg", "封面上传失败：" + e.getMessage());
            return result;
        }

        Optional<RecordRoom> roomOptional = roomRepository.findById(id);
        if (roomOptional.isPresent()) {
            try {
                RecordRoom room = roomOptional.get();
                Long userId = room.getUploadUserId();
                if (userId == null) {
                    result.put("type", "warning");
                    result.put("msg", "房间未绑定上传用户");
                    return result;
                }
                Optional<BiliBiliUser> userOptional = userRepository.findById(userId);
                if (!userOptional.isPresent()) {
                    result.put("type", "warning");
                    result.put("msg", "房间未绑定上传用户");
                    return result;
                }
                BiliBiliUser user = userOptional.get();
                byte[] bytes = file.getBytes();
                String response = BiliApi.uploadCover(user, file.getName(), bytes);
                String url = JsonPath.read(response, "data.url");
                if (StringUtils.isNotBlank(url)) {
                    room.setCoverUrl(url);
                    roomRepository.save(room);
                    result.put("type", "success");
                    result.put("coverUrl", url);
                    result.put("msg", "封面上传成功");
                    return result;
                }

            } catch (IOException e) {
                result.put("type", "warning");
                result.put("msg", "封面上传失败：" + e.getMessage());
                return result;
            }
            result.put("type", "warning");
            result.put("msg", "封面上传失败");
            return result;
        } else {
            result.put("type", "warning");
            result.put("msg", "房间不存在");
            return result;
        }
    }

    @GetMapping("/lines")
    public UploadEnums[] lines() {
        return UploadEnums.values();
    }

    @GetMapping("/test-lines")
    public Map<String, String> testLines() {
        Map<String, String> result = new HashMap<>();
        try {
            // 1. 获取官方线路列表
            URL url = new URL("https://member.bilibili.com/preupload?r=ping&file=lines.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            if (conn.getResponseCode() == 200) {
                String jsonStr = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                List<Map<String, String>> officialLines = JSON.parseObject(jsonStr, new TypeReference<List<Map<String, String>>>(){});
                
                // 2. 准备并发测速
                // 使用足够的线程数以确保所有线路能同时开始测速，避免排队等待
                ExecutorService executor = Executors.newFixedThreadPool(UploadEnums.values().length);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                Map<String, String> queryToUrl = new HashMap<>();
                
                for (Map<String, String> line : officialLines) {
                    queryToUrl.put(line.get("query"), line.get("url"));
                }

                // 3. 遍历本地枚举进行测速
                for (UploadEnums enumItem : UploadEnums.values()) {
                    // 去掉 lineQuery 开头的 ? 号来匹配
                    String queryKey = enumItem.getLineQuery().substring(1);
                    String testUrlStr = queryToUrl.get(queryKey);
                    
                    if (testUrlStr != null) {
                        if (testUrlStr.startsWith("//")) {
                            testUrlStr = "https:" + testUrlStr;
                        }
                        
                        String finalTestUrl = testUrlStr;
                        futures.add(CompletableFuture.runAsync(() -> {
                            long start = System.currentTimeMillis();
                            try {
                                URL testUrl = new URL(finalTestUrl);
                                HttpURLConnection testConn = (HttpURLConnection) testUrl.openConnection();
                                testConn.setRequestMethod("GET");
                                testConn.setConnectTimeout(3000);
                                testConn.setReadTimeout(3000);
                                
                                if (testConn.getResponseCode() == 200) {
                                    long cost = System.currentTimeMillis() - start;
                                    synchronized (result) {
                                        result.put(enumItem.getLine(), cost + "ms");
                                    }
                                } else {
                                    synchronized (result) {
                                        result.put(enumItem.getLine(), "Error " + testConn.getResponseCode());
                                    }
                                }
                            } catch (Exception e) {
                                synchronized (result) {
                                    result.put(enumItem.getLine(), "Timeout");
                                }
                            }
                        }, executor));
                    } else {
                        result.put(enumItem.getLine(), "Unknown");
                    }
                }
                
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                executor.shutdown();
            }
        } catch (Exception e) {
            log.warn("[BLR] {}", LogKvs.event("UploadLine.TestAll.Failed")
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
        return result;
    }

    @GetMapping("/test-speed")
    public Map<String, Object> testSpeed(@RequestParam String line) {
        Map<String, Object> result = new HashMap<>();
        try {
            UploadEnums enumItem = UploadEnums.find(line);
            // 1. 获取官方线路列表
            URL url = new URL("https://member.bilibili.com/preupload?r=ping&file=lines.json");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            if (conn.getResponseCode() == 200) {
                String jsonStr = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                List<Map<String, String>> officialLines = JSON.parseObject(jsonStr, new TypeReference<List<Map<String, String>>>() {});

                String queryKey = enumItem.getLineQuery().substring(1);
                String testUrlStr = null;
                for (Map<String, String> officialLine : officialLines) {
                    if (officialLine.get("query").equals(queryKey)) {
                        testUrlStr = officialLine.get("url");
                        break;
                    }
                }

                if (testUrlStr != null) {
                    if (testUrlStr.startsWith("//")) {
                        testUrlStr = "https:" + testUrlStr;
                    }
                    // 构造 1MB 数据
                    int size = 1 * 1024 * 1024;
                    byte[] data = new byte[size];
                    new Random().nextBytes(data);

                    long start = System.currentTimeMillis();
                    URL testUrl = new URL(testUrlStr + "?line=1"); // line=1 表示 1MB，参考官方 ping.js
                    HttpURLConnection testConn = (HttpURLConnection) testUrl.openConnection();
                    testConn.setRequestMethod("POST");
                    testConn.setDoOutput(true);
                    testConn.setConnectTimeout(5000);
                    testConn.setReadTimeout(10000); // 上传可能较慢，给多点时间

                    try (OutputStream os = testConn.getOutputStream()) {
                        os.write(data);
                        os.flush();
                    }

                    if (testConn.getResponseCode() == 200) {
                        long cost = System.currentTimeMillis() - start;
                        // 计算速度 MB/s
                        double speed = (double) size / 1024 / 1024 / ((double) cost / 1000);
                        result.put("speed", String.format("%.2f MB/s", speed));
                        result.put("cost", cost);
                        result.put("success", true);
                    } else {
                        result.put("success", false);
                        result.put("msg", "Error " + testConn.getResponseCode());
                    }
                } else {
                    result.put("success", false);
                    result.put("msg", "Unknown Line");
                }
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("msg", "Timeout/Error");
            log.warn("[BLR] {}", LogKvs.event("UploadLine.TestSpeed.Failed")
                    .add("line", line)
                    .addIfNotBlank("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
        }
        return result;
    }

    @GetMapping("/verification")
    public String verification(String template) {
        template = template.replace("${uname}", "主播名称")
                .replace("${title}", "直播标题")
                .replace("${roomId}", "房间号");
        if (template.contains("${")) {
            String date = template.substring(template.indexOf("${"), template.indexOf("}") + 1);
            String format = LocalDateTime.now().format(DateTimeFormatter.ofPattern(date.substring(2, date.length() - 1)));
            template = template.replace(date, format);
        }


        return template;
    }

    @GetMapping("/seasons/{roomId}")
    public String seasons(@PathVariable("roomId") Long roomId) {
        Optional<RecordRoom> roomOptional = roomRepository.findById(roomId);
        if (roomOptional.isPresent()) {
            RecordRoom room = roomOptional.get();
            if (room.getUploadUserId() != null) {
                BiliBiliUser biliUser = userRepository.findById(room.getUploadUserId()).get();
                int attempt = 0;
                while (true) {
                    try {
                        String raw = BiliApi.getSeasons(biliUser);
                        if (StringUtils.isBlank(raw)) {
                            log.warn("[BLR] {}", LogKvs.event("Room.Seasons.FetchFailed")
                                    .add("roomId", roomId)
                                    .add("uploadUserId", room.getUploadUserId())
                                    .add("timeout", false)
                                    .add("attempt", attempt)
                                    .add("reason", "empty_response"));
                            return "{\"code\":-1,\"message\":\"获取合集失败\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
                        }

                        try {
                            Map<String, Object> obj = JSON.parseObject(raw, new TypeReference<Map<String, Object>>() {});
                            Object dataObj = obj.get("data");
                            Map<String, Object> data;
                            if (dataObj instanceof Map) {
                                data = (Map<String, Object>) dataObj;
                            } else {
                                data = new HashMap<>();
                                obj.put("data", data);
                            }
                            Object seasons = data.get("seasons");
                            if (!(seasons instanceof List)) {
                                data.put("seasons", new ArrayList<>());
                            }
                            if (!obj.containsKey("ttl")) {
                                obj.put("ttl", 1);
                            }
                            return JSON.toJSONString(obj);
                        } catch (Exception ignored) {
                            return raw;
                        }
                    } catch (RuntimeException e) {
                        boolean timeout = StringUtils.containsIgnoreCase(e.getMessage(), "timed out");
                        if (timeout && attempt < 1) {
                            attempt++;
                            try { Thread.sleep(200L); } catch (Exception ignored) {}
                            continue;
                        }
                        log.warn("[BLR] {}", LogKvs.event("Room.Seasons.FetchFailed")
                                .add("roomId", roomId)
                                .add("uploadUserId", room.getUploadUserId())
                                .add("timeout", timeout)
                                .add("attempt", attempt)
                                .addIfNotBlank("err", e.getMessage())
                                .add("ex", e.getClass().getSimpleName()), e);
                        return "{\"code\":-1,\"message\":\"获取合集失败\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
                    }
                }
            }
        }
        // 前端 dataType=json 且会访问 data.data.seasons，这里返回一个最小可用结构，避免空指针
        return "{\"code\":-1,\"message\":\"未配置上传用户\",\"ttl\":1,\"data\":{\"seasons\":[]}}";
    }

    @GetMapping(value = "/image-proxy")
    public ResponseEntity<byte[]> imageProxy(@RequestParam("url") String url) {
        if (url == null || url.isBlank()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || (!host.endsWith(".hdslb.com") && !host.endsWith(".biliimg.com"))) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.InvalidHost").add("host", host));
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }
            if (scheme == null || (!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.InvalidScheme").add("scheme", scheme));
                return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
            }

            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3000);
            factory.setReadTimeout(5000);
            RestTemplate restTemplate = new RestTemplate(factory);

            HttpHeaders forwardHeaders = new HttpHeaders();
            forwardHeaders.add("Referer", "https://www.bilibili.com/");
            forwardHeaders.add("User-Agent", "Mozilla/5.0");
            forwardHeaders.add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8");
            HttpEntity<Void> httpEntity = new HttpEntity<>(forwardHeaders);

            ResponseEntity<byte[]> resp = restTemplate.exchange(url, HttpMethod.GET, httpEntity, byte[].class);
            byte[] imageBytes = resp.getBody();
            MediaType ct = resp.getHeaders().getContentType();

            if (!resp.getStatusCode().is2xxSuccessful()) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.UpstreamNon2xx")
                        .add("url", url)
                        .add("status", resp.getStatusCode().value()));
                return new ResponseEntity<>(HttpStatus.BAD_GATEWAY);
            }
            if (imageBytes == null || imageBytes.length == 0) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.EmptyBody")
                        .add("url", url)
                        .add("contentType", ct == null ? "" : ct.toString()));
                return new ResponseEntity<>(HttpStatus.BAD_GATEWAY);
            }
            if (ct != null && !"image".equalsIgnoreCase(ct.getType())) {
                log.warn("[BLR] {}", LogKvs.event("ImageProxy.NonImageContentType")
                        .add("url", url)
                        .add("contentType", ct.toString()));
                return new ResponseEntity<>(HttpStatus.BAD_GATEWAY);
            }

            HttpHeaders headers = new HttpHeaders();
            if (ct == null) {
                String path = uri.getPath();
                ct = MediaTypeFactory.getMediaType(path).orElse(MediaType.IMAGE_JPEG);
            }
            headers.setContentType(ct);
            headers.setCacheControl("public, max-age=604800");

            return new ResponseEntity<>(imageBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("ImageProxy.Failed")
                    .add("url", url)
                    .add("err", e.getMessage()));
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
