package top.sshh.bililiverecoder.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.service.FrontendVersionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
@RequestMapping("/")
public class IndexController {

    private final FrontendVersionService frontendVersionService;

    public IndexController(FrontendVersionService frontendVersionService) {
        this.frontendVersionService = frontendVersionService;
    }

    @GetMapping(value = "/index.html", produces = MediaType.TEXT_HTML_VALUE)
    public void getIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String content = frontendVersionService.readStaticText("static/index.html");
        if (content == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        frontendVersionService.setNoStoreHeaders(response);
        response.getWriter().write(frontendVersionService.renderHtml(content));
    }

    @GetMapping(value = {"", "/"}, produces = MediaType.TEXT_HTML_VALUE)
    public void getRoot(HttpServletRequest request, HttpServletResponse response) throws IOException {
        getIndex(request, response);
    }
}
