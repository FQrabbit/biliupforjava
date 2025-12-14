package top.sshh.bililiverecoder.entity.data;

import lombok.Data;

@Data
public class BiliLiveRoomInfoResponse {
    private int code;
    private String msg;
    private String message;
    private RoomInfo data;

    @Data
    public static class RoomInfo {
        private Long room_id;
        private Integer live_status; // 0: 空闲, 1: 直播中, 2: 轮播中
        private String title;
        private String live_time;
    }
}
