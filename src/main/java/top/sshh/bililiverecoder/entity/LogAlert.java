package top.sshh.bililiverecoder.entity;

import lombok.Data;
import java.util.Date;

@Data
public class LogAlert {
    private String type;
    private String message;
    private Date timestamp;
    private String level;
    private boolean read;

    public LogAlert(String type, String message, String level) {
        this.type = type;
        this.message = message;
        this.level = level;
        this.timestamp = new Date();
        this.read = false;
    }
}
