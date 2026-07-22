package top.sshh.bililiverecoder.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class DiagnosticExportRequest {

    public enum Mode { GLOBAL, HISTORY }

    private Mode mode = Mode.GLOBAL;
    private Long historyId;
    private int days = 3;
    private boolean includeFullLogs;
    private boolean includeRoomConfig = true;
    private boolean includeSystemConfig = true;
    private LocalDateTime occurredAt;
    private String note;
}
