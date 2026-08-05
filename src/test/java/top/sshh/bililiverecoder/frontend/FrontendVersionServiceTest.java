package top.sshh.bililiverecoder.frontend;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.service.FrontendVersionService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendVersionServiceTest {

    @Test
    void renderedHtmlInjectsNormalizedContextPath() {
        FrontendVersionService service = new FrontendVersionService();
        String rendered = service.renderHtml(
                "<html><head></head><body></body></html>",
                "/biliup/"
        );

        assertTrue(rendered.contains("window.BILIUPFORJAVA_CONTEXT_PATH='/biliup'"));
    }

    @Test
    void rootContextPathIsInjectedAsEmpty() {
        FrontendVersionService service = new FrontendVersionService();
        String rendered = service.renderHtml(
                "<html><head></head><body></body></html>",
                "/"
        );

        assertTrue(rendered.contains("window.BILIUPFORJAVA_CONTEXT_PATH=''"));
    }
}
