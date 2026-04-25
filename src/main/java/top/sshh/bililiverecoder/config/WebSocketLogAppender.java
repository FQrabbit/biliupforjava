package top.sshh.bililiverecoder.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sshh.bililiverecoder.service.LogAnalyzeService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class WebSocketLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }

        try {
            String rawMessage = event.getFormattedMessage();
            String stackTrace = event.getThrowableProxy() == null ? null : ThrowableProxyUtil.asString(event.getThrowableProxy());
            String displayMessage = appendStackTrace(rawMessage, stackTrace);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(event.getTimeStamp())));
            payload.put("level", event.getLevel().toString());
            payload.put("thread", event.getThreadName());
            payload.put("logger", event.getLoggerName());
            payload.put("message", displayMessage);
            if (stackTrace != null && !stackTrace.isBlank()) {
                payload.put("stackTrace", stackTrace);
            }

            LogWebSocketHandler.sendLog(toJson(payload));

            LogAnalyzeService service = LogAnalyzeService.getInstance();
            if (service != null) {
                service.processLog(rawMessage, event.getLevel().toString());
            }
        } catch (Exception e) {
            // 避免日志发送失败导致死循环，这里直接吞掉异常
        }
    }

    private static String appendStackTrace(String message, String stackTrace) {
        String safeMessage = message == null ? "" : message;
        if (stackTrace == null || stackTrace.isBlank()) {
            return safeMessage;
        }
        if (safeMessage.isBlank()) {
            return stackTrace;
        }
        return safeMessage + System.lineSeparator() + stackTrace;
    }

    private static String toJson(Map<String, Object> payload) throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsString(payload);
    }
}
