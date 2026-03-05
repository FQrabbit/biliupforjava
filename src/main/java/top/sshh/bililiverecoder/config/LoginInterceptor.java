package top.sshh.bililiverecoder.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import top.sshh.bililiverecoder.util.LogKvs;

import java.util.Base64;

@Slf4j
public class LoginInterceptor implements HandlerInterceptor {

    public LoginInterceptor(String userName,String password) {
        if(StringUtils.isNotBlank(userName) && StringUtils.isNotBlank(password)){
            Base64.Encoder encoder = Base64.getEncoder();
            this.authString = "Basic " + encoder.encodeToString((userName+":"+password).getBytes());
            log.info("[BLR] {}", LogKvs.event("Auth.Basic.Enabled"));
        }else {
            this.authString = "";
            log.info("[BLR] {}", LogKvs.event("Auth.Basic.DisabledByConfig"));
        }
    }

    private final String authString;
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        if(StringUtils.isBlank(authString)){
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (StringUtils.isBlank(authorization)) {
            authorization = request.getParameter("auth");
        }
        if(this.authString.equals(authorization)){
            return true;
        }
        
        log.info("[BLR] {}", LogKvs.event("Auth.Basic.Failed")
            .add("ip", request.getRemoteAddr())
            .add("path", request.getRequestURI()));

        String accept = request.getHeader("Accept");
        if (StringUtils.contains(accept, "text/html") && !"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.sendRedirect("/html/login.html");
            return false;
        }
        
        // response.setHeader("WWW-Authenticate", "Basic realm=\"Restricted\"");
        response.setStatus(401);
        return false;
    }
}
