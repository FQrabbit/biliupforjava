package top.sshh.bililiverecoder.protection;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiskProtectionHttpTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    String auth = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @BeforeEach void setup() {
        var settings = DiskSpaceSettings.from(new MockEnvironment().withProperty("record.work-path", directory.toString())
                .withProperty("record.userName", "user").withProperty("record.password", "pass").withProperty("server.servlet.context-path", "/app"));
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe(), new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard); guard.protect("test");
    }
    @AfterEach void cleanup() { guard.close(); }

    @Test void statusAndPageRequireExistingCredentials() throws Exception {
        assertEquals(401, DiskProtectionHttp.handle(guard,"GET","/api/system/disk-protection",null,null,null).status());
        assertEquals(200, DiskProtectionHttp.handle(guard,"GET","/api/system/disk-protection",auth,null,null).status());
        var page = DiskProtectionHttp.handle(guard,"GET","/disk-protection",auth,null,"text/html");
        String html = new String(page.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(200, page.status()); assertTrue(html.contains(guard.csrfToken()));
        assertFalse(html.contains("__CONTEXT_PATH__")); assertTrue(html.contains("const base='/app'"));
    }

    @Test void webhookIsRejectedBeforeParsingOrWritingPayload() throws Exception {
        for (String path : List.of("/recordWebHook", "/webhook/blrec")) {
            assertEquals(503, DiskProtectionHttp.handle(guard,"POST",path,null,null,null).status());
        }
        assertFalse(java.nio.file.Files.exists(guard.settings().marker()));
    }

    @Test void restoreRequiresPostAndPerPageToken() throws Exception {
        assertEquals(403, DiskProtectionHttp.handle(guard,"POST","/api/system/disk-protection/resume",auth,"wrong",null).status());
        assertEquals(503, DiskProtectionHttp.handle(guard,"GET","/api/system/disk-protection/resume",auth,null,null).status());
        assertEquals(409, DiskProtectionHttp.handle(guard,"POST","/api/system/disk-protection/resume",auth,guard.csrfToken(),null).status());
    }

    @Test void filterInterceptsBeforeControllersAndHandlesContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/recordWebHook"); request.setContextPath("/app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DiskProtectionFilter().doFilter(request, response, (req, res) -> fail("不应进入业务控制器"));
        assertEquals(503, response.getStatus()); assertEquals("60", response.getHeader("Retry-After"));
        assertEquals("no-store", response.getHeader("Cache-Control")); assertTrue(ProtectionRequests.isIdle());
    }
}
