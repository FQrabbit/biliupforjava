package top.sshh.bililiverecoder.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import top.sshh.bililiverecoder.service.LogAnalyzeService;

import java.text.SimpleDateFormat;
import java.util.Date;

public class WebSocketLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        try {
             StringBuilder json = new StringBuilder();
             json.append("{");
             json.append("\"timestamp\": \"").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(event.getTimeStamp()))).append("\",");
             json.append("\"level\": \"").append(event.getLevel().toString()).append("\",");
             json.append("\"thread\": \"").append(event.getThreadName()).append("\",");
             json.append("\"logger\": \"").append(event.getLoggerName()).append("\",");
             
             String msg = event.getFormattedMessage();
             String rawMsg = msg; // Keep raw message for analysis

             if(msg != null) {
                 msg = msg.replace("\\", "\\\\")
                          .replace("\"", "\\\"")
                          .replace("\n", "\\n")
                          .replace("\r", "");
             } else {
                 msg = "";
             }
             json.append("\"message\": \"").append(msg).append("\"");
             json.append("}");
             
             LogWebSocketHandler.sendLog(json.toString());

             // Analyze for Alerts
             LogAnalyzeService service = LogAnalyzeService.getInstance();
             if (service != null) {
                 service.processLog(rawMsg, event.getLevel().toString());
             }
        } catch (Exception e) {
            // ignore
        }
    }
}
