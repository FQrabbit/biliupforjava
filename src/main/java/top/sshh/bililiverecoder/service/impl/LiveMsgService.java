package top.sshh.bililiverecoder.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dom4j.Element;
import org.dom4j.ElementHandler;
import org.dom4j.ElementPath;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.*;
import top.sshh.bililiverecoder.entity.data.BiliDmResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.repo.LiveMsgRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.RecordRoomRepository;
import top.sshh.bililiverecoder.util.BiliApi;
import top.sshh.bililiverecoder.util.LogKvs;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

@Slf4j
@Transactional
@Component
public class LiveMsgService {


    @Autowired
    private JdbcService jdbcService;

    @Autowired
    private LiveMsgRepository liveMsgRepository;
    @Autowired
    private RecordHistoryRepository recordHistoryRepository;
    @Autowired
    private RecordRoomRepository roomRepository;

    @Autowired
    RecordHistoryPartRepository partRepository;

    public int sendMsg(BiliBiliUser user, LiveMsg liveMsg) {
        try {
            BiliDmResponse response = BiliApi.sendVideoDm(user, liveMsg);
            if (response == null) {
                log.error("[BLR] {}", LogKvs.event("LiveMsg.Send.EmptyResponse")
                        .add("uname", user.getUname())
                        .add("bvid", liveMsg.getBvid())
                        .add("cid", liveMsg.getCid())
                        .add("partId", liveMsg.getPartId()));
                return -1;
            }
            int code = response.getCode();
            if (code != 0) {
                log.warn("[BLR] {}", LogKvs.event("LiveMsg.Send.Failed")
                        .add("uname", user.getUname())
                        .add("code", code)
                        .add("respMsg", response.getMessage())
                        .add("bvid", liveMsg.getBvid())
                        .add("cid", liveMsg.getCid())
                        .add("partId", liveMsg.getPartId()));
                if (code == 36701 || code == 36702 || code == 36714) {
                    liveMsgRepository.delete(liveMsg);
                }
                if(code == 36704){
                    String bvid = liveMsg.getBvid();
                    this.syncVideoState(bvid);
                    return code;
                }
            }
            liveMsg.setCode(code);
            liveMsgRepository.save(liveMsg);
            return code;
        } catch (Exception e) {
            log.error("[BLR] {}", LogKvs.event("LiveMsg.Send.Error")
                    .add("uname", user.getUname())
                    .add("bvid", liveMsg.getBvid())
                    .add("cid", liveMsg.getCid())
                    .add("partId", liveMsg.getPartId())
                    .add("err", e.getMessage())
                    .add("ex", e.getClass().getSimpleName()), e);
            return -2;
        }
    }

    public static boolean checkUtf8Size(String testStr) {
        for (int i = 0; i < testStr.length(); i++) {
            int c = testStr.codePointAt(i);
            if (c < 0x0000 || c > 0xffff) {
                return true;
            }
        }
        return false;
    }

    public void processing(RecordHistoryPart part) {
        Optional<RecordHistory> historyOptional = recordHistoryRepository.findById(part.getHistoryId());
        String bvid = historyOptional.map(RecordHistory::getBvId).orElse("");

        RecordRoom room = roomRepository.findByRoomId(part.getRoomId());
        String dmKeywordBlacklist = room.getDmKeywordBlacklist();
        String[] EXCLUSION_DM;
        if (StringUtils.isNotBlank(dmKeywordBlacklist)) {
            EXCLUSION_DM = dmKeywordBlacklist.split("\\r?\\n");
            // 清理每一项的空白字符
            for (int i = 0; i < EXCLUSION_DM.length; i++) {
                EXCLUSION_DM[i] = EXCLUSION_DM[i].trim();
            }
        } else {
            EXCLUSION_DM = new String[0];
        }
        String filePath = part.getFilePath();
        String xmlFilePath = filePath.substring(0, filePath.lastIndexOf(".")) + ".xml";
        File file = new File(xmlFilePath);
        boolean exists = file.exists();
        if (exists) {
            // 解析前先清理可能存在的旧数据（幂等性保证）
            liveMsgRepository.deleteByPartId(part.getId());

            FileInputStream stream = null;
            try {
                stream = new FileInputStream(file);

                // 创建SAXReader对象
                SAXReader saxReader = new SAXReader();
                // 禁用安全限制以支持大文件
                try {
                    saxReader.setFeature("http://javax.xml.XMLConstants/feature/secure-processing", false);
                } catch (Exception e) {
                    log.warn("[BLR] {}", LogKvs.event("LiveMsg.Parse.DisableSecureFailed")
                            .add("filePath", xmlFilePath)
                            .add("partId", part.getId())
                            .add("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }

                List<LiveMsg> liveMsgs = new ArrayList<>();
                BloomFilter<CharSequence> bloomFilter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 1000000, 0.01);
                
                // 进度计数器
                final int[] totalCount = {0};

                Consumer<LiveMsg> msgSaver = msg -> {
                    // 确保内容不超长（数据库字段通常限制在255或更小）
                    if (msg.getContext() != null && msg.getContext().length() > 200) {
                        msg.setContext(msg.getContext().substring(0, 199));
                    }
                    liveMsgs.add(msg);
                    totalCount[0]++;
                    
                    if (liveMsgs.size() >= 500) {
                        jdbcService.saveLiveMsgList(liveMsgs);
                        if (totalCount[0] % 10000 == 0) {
                            log.info("[BLR] {}", LogKvs.event("LiveMsg.Parse.Progress")
                                    .add("filePath", xmlFilePath)
                                    .add("total", totalCount[0])
                                    .add("partId", part.getId()));
                        }
                        liveMsgs.clear();
                    }
                };

                // sc弹幕处理
                saxReader.addHandler("/i/sc", new ElementHandler() {
                    @Override
                    public void onStart(ElementPath path) {
                    }

                    @Override
                    public void onEnd(ElementPath path) {
                        Element element = path.getCurrent();
                        String time = element.attribute("ts").getValue();
                        long sendTime = Math.round(Double.parseDouble(time) * 1000);
                        // 边界检查：如果弹幕时间超过视频时长，跳过（仅在时长已知时执行）
                        if (part.getDuration() > 0 && sendTime > (long) (part.getDuration() * 1000)) {
                            element.detach();
                            return;
                        }
                        String userName = element.attribute("user").getValue();
                        String price = element.attribute("price").getValue();

                        //  blrec 弹幕的金额/1000
                        if ("blrec".equals(part.getSourceType())) {
                            price = String.valueOf(Integer.parseInt(price) / 1000);
                        }

                        String text = element.getText();
                        LiveMsg msg = new LiveMsg();
                        msg.setPartId(part.getId());
                        msg.setBvid(bvid);
                        msg.setCid(part.getCid());
                        msg.setSendTime(sendTime);
                        msg.setMode(5);
                        msg.setPool(1);
                        msg.setFontsize(64);
                        msg.setColor(16776960);
                        StringBuilder builder = new StringBuilder();
                        builder.append("SC [").append(price).append("] ").append(userName).append(": ").append(text);
                        if (builder.length() > 100) {
                            text = builder.substring(0, 99);
                        } else {
                            text = builder.toString();
                        }
                        msg.setContext(text);
                        msgSaver.accept(msg);
                        element.detach();
                    }
                });

                // sc弹幕处理(Guard)
                saxReader.addHandler("/i/guard", new ElementHandler() {
                    @Override
                    public void onStart(ElementPath path) {
                    }

                    @Override
                    public void onEnd(ElementPath path) {
                        Element element = path.getCurrent();
                        String time = element.attribute("ts").getValue();
                        long sendTime = Math.round(Double.parseDouble(time) * 1000);
                        // 边界检查：如果弹幕时间超过视频时长，跳过（仅在时长已知时执行）
                        if (part.getDuration() > 0 && sendTime > (long) (part.getDuration() * 1000)) {
                            element.detach();
                            return;
                        }
                        String userName = element.attribute("user").getValue();
                        String level = element.attribute("level").getValue();
                        String count = element.attribute("count").getValue();
                        LiveMsg msg = new LiveMsg();
                        msg.setPartId(part.getId());
                        msg.setBvid(bvid);
                        msg.setCid(part.getCid());
                        msg.setSendTime(sendTime);
                        msg.setMode(5);
                        msg.setPool(1);
                        msg.setColor(16776960);
                        StringBuilder builder = new StringBuilder();
                        builder.append("⚓").append(userName).append(": 开通了");
                        if (Integer.parseInt(count) > 1) {
                            builder.append(count).append("个月");
                        }
                        if ("1".equals(level)) {
                            msg.setFontsize(64);
                            builder.append("总督");
                        } else if ("2".equals(level)) {
                            msg.setFontsize(64);
                            builder.append("提督");
                        } else if ("3".equals(level)) {
                            msg.setFontsize(64);
                            builder.append("舰长");
                        } else {
                            builder.append("舰长");
                        }
                        String text;
                        if (builder.length() > 100) {
                            text = builder.substring(0, 99);
                        } else {
                            text = builder.toString();
                        }
                        msg.setContext(text);
                        msgSaver.accept(msg);
                        element.detach();
                    }
                });

                // 普通弹幕处理
                saxReader.addHandler("/i/d", new ElementHandler() {
                    @Override
                    public void onStart(ElementPath path) {
                    }

                    @Override
                    public void onEnd(ElementPath path) {
                        Element element = path.getCurrent();
                        String text = element.getText().trim().replace("\n", ",").replace("\r", ",").toLowerCase();
                        text = StringUtils.deleteWhitespace(text);
                        //过滤utf8字符大小为4的
                        if (checkUtf8Size(text)) {
                            element.detach();
                            return;
                        }
                        //排除垃圾弹幕
                        boolean isContinue = false;
                        for (String s : EXCLUSION_DM) {
                            if (text.contains(s.toLowerCase())) {
                                isContinue = true;
                                break;
                            }
                        }
                        if (isContinue) {
                            element.detach();
                            return;
                        }
                        if (element.attribute("raw") != null) {
                            String raw = element.attribute("raw").getValue();
                            JSONArray array = JSON.parseArray(raw);

                            // 判断是否抽奖弹幕
                            boolean lottery = (Integer) ((JSONArray) array.get(0)).get(9) != 0;
                            if (lottery) {
                                element.detach();
                                return;
                            }

                            JSONArray dmFanMedalObjects = (JSONArray) array.get(3);
                            // 0-不做处理，1-必须佩戴粉丝勋章。2-必须佩戴主播的粉丝勋章
                            if (room.getDmFanMedal() == 1) {
                                if (dmFanMedalObjects.size() == 0) {
                                    element.detach();
                                    return;
                                }
                            } else if (room.getDmFanMedal() == 2) {
                                if (dmFanMedalObjects.size() == 0) {
                                    element.detach();
                                    return;
                                }
                                String roomId = dmFanMedalObjects.get(3).toString();
                                if (!part.getRoomId().equals(roomId)) {
                                    element.detach();
                                    return;
                                }
                            }
                            Integer ulLive = (Integer) ((JSONArray) array.get(4)).get(0);
                            //排除低级用户
                            if (ulLive < room.getDmUlLevel()) {
                                if (dmFanMedalObjects.size() == 0) {
                                    element.detach();
                                    return;
                                }
                            }
                        }

                        String value = element.attribute("p").getValue();
                        String[] values = value.split(",");
                        long sendTime = Math.round(Double.parseDouble(values[0]) * 1000);
                        if (sendTime < 0) {
                            element.detach();
                            return;
                        }
                        // 边界检查：如果弹幕时间超过视频时长，跳过（仅在时长已知时执行）
                        if (part.getDuration() > 0 && sendTime > (long) (part.getDuration() * 1000)) {
                            element.detach();
                            return;
                        }
                        int fontsize = Integer.parseInt(values[2]);
                        int color = Integer.parseInt(values[3]);
                        //弹幕重复过滤
                        if (room.getDmDistinct() != null && room.getDmDistinct()) {
                            if (!bloomFilter.put(text)) {
                                element.detach();
                                return;
                            }
                        }
                        LiveMsg msg = new LiveMsg();
                        msg.setPartId(part.getId());
                        msg.setBvid(bvid);
                        msg.setCid(part.getCid());
                        msg.setSendTime(sendTime);
                        msg.setFontsize(fontsize);
                        msg.setMode(1);
                        msg.setPool(0);
                        msg.setColor(color);
                        msg.setContext(text);
                        msgSaver.accept(msg);
                        element.detach();
                    }
                });

                saxReader.read(stream);

                if (!liveMsgs.isEmpty()) {
                    jdbcService.saveLiveMsgList(liveMsgs);
                    log.info("[BLR] {}", LogKvs.event("LiveMsg.Parse.Saved")
                            .add("filePath", xmlFilePath)
                            .add("count", liveMsgs.size())
                            .add("roomId", part.getRoomId())
                            .add("partId", part.getId())
                            .add("historyId", part.getHistoryId()));
                }
            } catch (Exception e) {
                log.error("[BLR] {}", LogKvs.event("LiveMsg.Parse.Failed")
                        .add("filePath", xmlFilePath)
                        .add("roomId", part.getRoomId())
                        .add("partId", part.getId())
                        .add("historyId", part.getHistoryId())
                        .add("err", e.getMessage())
                        .add("ex", e.getClass().getSimpleName()), e);
                // 抛出异常以触发事务回滚，避免数据不一致（如：旧数据已删但新数据只入库一半）
                throw new RuntimeException("LiveMsg parse failed", e);
            } finally {
                try {
                    if (stream != null) {
                        stream.close();
                    }
                } catch (IOException e) {
                    log.warn("[BLR] {}", LogKvs.event("LiveMsg.Parse.CloseFailed")
                            .add("filePath", xmlFilePath)
                            .add("roomId", part.getRoomId())
                            .add("partId", part.getId())
                            .add("historyId", part.getHistoryId())
                            .addIfNotBlank("err", e.getMessage())
                            .add("ex", e.getClass().getSimpleName()), e);
                }
            }
        }
    }
    
    
    public void syncVideoState(String bvid) {
        RecordHistory history = recordHistoryRepository.findByBvId(bvid);
        BiliVideoInfoResponse videoInfoResponse = BiliApi.getVideoInfo(null,history.getBvId());
        int code = videoInfoResponse.getCode();
        BiliVideoInfoResponse.BiliVideoInfo videoInfoResponseData = videoInfoResponse.getData();
        if (code != 0 || videoInfoResponseData.getState() != 0) {
            history.setCode(-1);
            history = recordHistoryRepository.save(history);
        }
    }
}
