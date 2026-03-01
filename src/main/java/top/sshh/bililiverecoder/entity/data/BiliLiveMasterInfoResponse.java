package top.sshh.bililiverecoder.entity.data;

import lombok.Data;

@Data
public class BiliLiveMasterInfoResponse {
    private int code;
    private String msg;
    private String message;
    private MasterInfo data;

    @Data
    public static class MasterInfo {
        private Info info;
    }

    @Data
    public static class Info {
        private Long uid;
        private String uname;
        private String face;
        private Integer gender; // -1: 保密, 0: 女, 1: 男
    }
}
