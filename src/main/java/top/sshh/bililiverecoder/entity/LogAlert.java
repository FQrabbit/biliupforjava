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
    private int count;
    private Date firstTime;
    private Date lastTime;

    public LogAlert(String type, String message, String level) {
        this.type = type;
        this.message = message;
        this.level = level;
        this.timestamp = new Date();
        this.firstTime = this.timestamp;
        this.lastTime = this.timestamp;
        this.read = false;
        this.count = 1;
    }

    public void incrementCount() {
        this.count++;
        this.lastTime = new Date();
        this.timestamp = this.lastTime;
    }
}
