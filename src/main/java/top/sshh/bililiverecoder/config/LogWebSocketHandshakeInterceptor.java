package top.sshh.bililiverecoder.config;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import top.sshh.bililiverecoder.service.LogWebSocketTicketService;

import java.util.Map;

@Component
public class LogWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    private final LogWebSocketTicketService ticketService;

    public LogWebSocketHandshakeInterceptor(LogWebSocketTicketService ticketService) {
        this.ticketService = ticketService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        MultiValueMap<String, String> query = org.springframework.web.util.UriComponentsBuilder
                .fromUri(request.getURI()).build().getQueryParams();
        if (!ticketService.consume(query.getFirst("ticket"))) {
            response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需额外处理
    }
}
