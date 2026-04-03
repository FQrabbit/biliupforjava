package top.sshh.bililiverecoder.entity.data;

import lombok.Data;

import java.util.List;

@Data
public class BiliVideoAuditDetailResponse {
    private int code;
    private String message;
    private AuditData data;

    @Data
    public static class AuditData {
        private long aid;
        private int state;
        private List<ProblemDetail> problem_detail;
        private Appeal appeal;
    }

    @Data
    public static class Appeal {
        private String reject;
    }

    @Data
    public static class ProblemDetail {
        private String type;
        private String reject_reason;
        private String reject_reason_url;
        private String modify_advise;
        private String problem_description;
        private String problem_description_title;
        private Long reject_reason_id;
        private Integer index;
        private String violation_time;
        private String violation_position;
    }
}
