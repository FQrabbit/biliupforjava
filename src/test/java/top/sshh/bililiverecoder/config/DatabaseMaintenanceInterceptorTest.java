package top.sshh.bililiverecoder.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceState;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMaintenanceInterceptorTest {
    public void endpoint() { }

    @Test
    void maintenanceStatusWebhooksAndStaticResourcesStayAvailableWhileQueriesReturnRetry() throws Exception {
        DatabaseMaintenanceState state = new DatabaseMaintenanceState();
        DatabaseMaintenanceInterceptor interceptor = new DatabaseMaintenanceInterceptor(state);
        HandlerMethod handler = new HandlerMethod(this, getClass().getMethod("endpoint"));
        var request = new MockHttpServletRequest("GET", "/stats/overview");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), handler));
        state.setMaintenanceActive(true);
        var response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, handler));
        assertEquals(503, response.getStatus());
        assertEquals("5", response.getHeader("Retry-After"));
        assertTrue(response.getContentAsString().contains("数据库正在维护"));
        for (String path : new String[]{"/stats/maintenance/status", "/stats/maintenance/compact", "/recordWebHook", "/webhook/blrec"}) {
            assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), handler));
        }
        assertTrue(interceptor.preHandle(new MockHttpServletRequest("GET", "/modules/pages/stats/desktop.html"),
                new MockHttpServletResponse(), new Object()));
        var prefixed = new MockHttpServletRequest("GET", "/app/stats/maintenance/status");
        prefixed.setContextPath("/app");
        assertTrue(interceptor.preHandle(prefixed, new MockHttpServletResponse(), handler));
    }
}
