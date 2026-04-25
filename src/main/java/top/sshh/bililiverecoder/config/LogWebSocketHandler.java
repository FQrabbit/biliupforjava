package top.sshh.bililiverecoder.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private static final int MAX_PENDING_LOGS = 2000;
    private static final LinkedBlockingDeque<String> pendingLogs = new LinkedBlockingDeque<>(MAX_PENDING_LOGS);
    private static final AtomicLong droppedLogs = new AtomicLong(0);
    private static final AtomicLong reportedDroppedLogs = new AtomicLong(0);

    static {
        Thread sender = new Thread(LogWebSocketHandler::drainLoop, "log-ws-sender");
        sender.setDaemon(true);
        sender.start();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
    }

    public static void sendLog(String log) {
        if (log == null) {
            return;
        }

        if (!pendingLogs.offerLast(log)) {
            pendingLogs.pollFirst();
            droppedLogs.incrementAndGet();
            if (!pendingLogs.offerLast(log)) {
                droppedLogs.incrementAndGet();
            }
        }
    }

    private static void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String log = pendingLogs.pollFirst(1, TimeUnit.SECONDS);
                if (log == null) {
                    continue;
                }

                sendDroppedLogNoticeIfNeeded();
                broadcast(log);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // Keep the sender alive even if one malformed log or session fails.
            }
        }
    }

    private static void sendDroppedLogNoticeIfNeeded() {
        long dropped = droppedLogs.get();
        long reported = reportedDroppedLogs.get();
        if (dropped <= reported || !reportedDroppedLogs.compareAndSet(reported, dropped)) {
            return;
        }

        long currentDropped = dropped - reported;
        broadcast(buildSystemLog("实时日志队列已满，已丢弃 " + currentDropped + " 条较旧消息，累计丢弃 " + dropped + " 条。"));
    }

    private static void broadcast(String log) {
        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                sessions.remove(session);
                continue;
            }

            try {
                Object nativeSession = getNativeSessionIfAvailable(session);
                if (!tryAsyncSend(nativeSession, log)) {
                    session.sendMessage(new TextMessage(log));
                }
            } catch (Exception e) {
                sessions.remove(session);
            }
        }
    }

    private static String buildSystemLog(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
        payload.put("level", "WARN");
        payload.put("thread", "log-ws-sender");
        payload.put("logger", LogWebSocketHandler.class.getName());
        payload.put("message", message);

        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"level\":\"WARN\",\"message\":\"Log websocket queue dropped messages.\"}";
        }
    }

    private static boolean tryAsyncSend(Object nativeSession, String log) {
        if (nativeSession == null) {
            return false;
        }
        try {
            Method isOpen = nativeSession.getClass().getMethod("isOpen");
            Object open = isOpen.invoke(nativeSession);
            if (!(open instanceof Boolean) || !((Boolean) open)) {
                return false;
            }

            Method getAsyncRemote = nativeSession.getClass().getMethod("getAsyncRemote");
            Object asyncRemote = getAsyncRemote.invoke(nativeSession);
            if (asyncRemote == null) {
                return false;
            }

            Method sendText = asyncRemote.getClass().getMethod("sendText", String.class);
            sendText.invoke(asyncRemote, log);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Object getNativeSessionIfAvailable(WebSocketSession session) {
        if (session == null) {
            return null;
        }
        try {
            Method getNativeSession = session.getClass().getMethod("getNativeSession");
            return getNativeSession.invoke(session);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
