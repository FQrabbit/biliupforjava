package top.sshh.bililiverecoder.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

/** 维护期间直接提示页面稍后重试，避免查询占满请求线程 */
public class DatabaseMaintenanceInterceptor implements HandlerInterceptor {
    private final DatabaseMaintenanceState state;

    public DatabaseMaintenanceInterceptor(DatabaseMaintenanceState state) {
        this.state = state;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!state.isMaintenanceActive() || !(handler instanceof HandlerMethod)) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/stats/maintenance/") || path.startsWith("/room/backup/status/")
                || path.startsWith("/room/backup/cancel/") || path.equals("/recordWebHook") || path.equals("/webhook/blrec")
                || path.equals("/error") || path.equals("/") || path.equals("/index.html")
                || path.equals("/api/version") || path.equals("/api/version/check")) return true;
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "5");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"maintenance\":true,\"message\":\"数据库正在维护，请稍后重试\"}");
        return false;
    }
}
