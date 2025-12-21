package top.sshh.bililiverecoder.config;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

    private static final CopyOnWriteArraySet<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    // 有界队列，防止慢速ws客户端阻塞业务线程或导致内存溢出
    private static final int MAX_PENDING_LOGS = 2000;
    private static final LinkedBlockingDeque<String> pendingLogs = new LinkedBlockingDeque<>(MAX_PENDING_LOGS);
    private static final AtomicLong droppedLogs = new AtomicLong(0);

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

        // 如果队列已满，丢弃最旧的日志以保留最新的日志
        if (!pendingLogs.offerLast(log)) {
            pendingLogs.pollFirst();
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

                for (WebSocketSession session : sessions) {
                    if (!session.isOpen()) {
                        sessions.remove(session);
                        continue;
                    }

                    try {
                        // 优先选择JSR-356异步远程通信
                        Object nativeSession = getNativeSessionIfAvailable(session);
                        if (!tryAsyncSend(nativeSession, log)) {
                            // 后备方案：Spring会话发送(可能会阻塞，但仅阻塞此发送线程)
                            session.sendMessage(new TextMessage(log));
                        }
                    } catch (Exception e) {
                        sessions.remove(session);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                // 绝不让发送线程终止
            }
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
