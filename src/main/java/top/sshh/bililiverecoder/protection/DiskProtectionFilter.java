package top.sshh.bililiverecoder.protection;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DiskProtectionFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        DiskSpaceGuard guard = DiskSpaceGuard.current();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (guard == null || (!DiskSpaceGuard.blocksBusiness() && !path.equals("/disk-protection")
                && !path.startsWith("/api/system/disk-protection"))) {
            if (ProtectionRequests.enter()) {
                try { chain.doFilter(request, response); } finally { ProtectionRequests.leave(); }
                return;
            }
        }
        DiskProtectionHttp.Response result = DiskProtectionHttp.handle(guard, request.getMethod(), path,
                request.getHeader("Authorization"), request.getHeader("X-Protection-Token"), request.getHeader("Accept"));
        response.setStatus(result.status()); response.setContentType(result.type());
        response.setHeader("Cache-Control", "no-store");
        if (result.status() == 503) response.setHeader("Retry-After", "60");
        if (result.challenge()) response.setHeader("WWW-Authenticate", "Basic realm=\"biliupforjava\", charset=\"UTF-8\"");
        response.getOutputStream().write(result.body());
    }
}
