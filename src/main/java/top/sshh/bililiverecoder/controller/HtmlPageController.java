package top.sshh.bililiverecoder.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.service.FrontendVersionService;

import java.io.IOException;
import java.util.Set;

@RestController
public class HtmlPageController {

    private static final Set<String> ALLOWED_HTML_PAGES = Set.of(
            "captcha",
            "history",
            "login",
            "room",
            "stats",
            "setup"
    );

    private final FrontendVersionService frontendVersionService;

    public HtmlPageController(FrontendVersionService frontendVersionService) {
        this.frontendVersionService = frontendVersionService;
    }

    @GetMapping(value = "/html/{page}.html", produces = MediaType.TEXT_HTML_VALUE)
    public void getHtmlPage(@PathVariable("page") String page, HttpServletResponse response) throws IOException {
        if (!ALLOWED_HTML_PAGES.contains(page)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        String content = frontendVersionService.readStaticText("static/html/" + page + ".html");
        if (content == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        frontendVersionService.setNoStoreHeaders(response);
        response.getWriter().write(frontendVersionService.renderHtml(content));
    }
}
