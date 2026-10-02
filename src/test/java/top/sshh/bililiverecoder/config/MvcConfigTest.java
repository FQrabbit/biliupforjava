package top.sshh.bililiverecoder.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MvcConfigTest {

    @Test
    void moduleAssetsRemainPublicWhenBasicAuthenticationIsEnabled() {
        MvcConfig config = new MvcConfig(mock(AsyncTaskExecutor.class), new top.sshh.bililiverecoder.service.DatabaseMaintenanceState());
        ReflectionTestUtils.setField(config, "userName", "user");
        ReflectionTestUtils.setField(config, "password", "password");

        InspectableInterceptorRegistry registry = new InspectableInterceptorRegistry();
        config.addInterceptors(registry);

        MappedInterceptor interceptor = (MappedInterceptor) registry.interceptors().get(0);
        assertFalse(matches(interceptor, "/modules/manifest.json"));
        assertFalse(matches(interceptor, "/modules/pages/stats/page.js"));
        assertFalse(matches(interceptor, "/modules/pages/history/mobile.html"));
        assertFalse(matches(interceptor, "/recordWebHook"));
        assertFalse(matches(interceptor, "/webhook/blrec"));
        assertTrue(matches(interceptor, "/nested/recordWebHook"));
        assertTrue(matches(interceptor, "/system-config/list"));
        assertTrue(matches(interceptor, "/webhook/blrec/inbox"));
        assertTrue(matches(interceptor, "/recordWebHook/inbox"));
    }

    private boolean matches(MappedInterceptor interceptor, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setServletPath(path);
        ServletRequestPathUtils.parseAndCache(request);
        return interceptor.matches(request);
    }

    private static final class InspectableInterceptorRegistry extends InterceptorRegistry {
        private List<Object> interceptors() {
            return getInterceptors();
        }
    }
}
