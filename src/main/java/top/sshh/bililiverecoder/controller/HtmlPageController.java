package top.sshh.bililiverecoder.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.service.FrontendVersionService;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.web.util.UriComponentsBuilder;

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

    private static final Set<String> ALLOWED_MOBILE_HTML_PAGES = Set.of(
            "history",
            "room",
            "stats"
    );

    private static final Set<String> MODULE_PAGES = Set.of("stats", "history", "room");

    private final FrontendVersionService frontendVersionService;

    public HtmlPageController(FrontendVersionService frontendVersionService) {
        this.frontendVersionService = frontendVersionService;
    }

    @GetMapping(value = "/html/{page}.html", produces = MediaType.TEXT_HTML_VALUE)
    public void getHtmlPage(@PathVariable("page") String page,
                            HttpServletRequest request,
                            HttpServletResponse response) throws IOException {
        if (!ALLOWED_HTML_PAGES.contains(page)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        if (MODULE_PAGES.contains(page)) {
            redirectToModulePage(page, false, request, response);
            return;
        }

        writeRenderedHtml("static/html/" + page + ".html", request.getContextPath(), response);
    }

    @GetMapping(value = "/mobile", produces = MediaType.TEXT_HTML_VALUE)
    public void redirectMobileIndex(HttpServletRequest request,
                                    HttpServletResponse response) throws IOException {
        UriComponentsBuilder target = UriComponentsBuilder.fromPath(request.getContextPath()).path("/mobile/");
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            for (String value : entry.getValue()) {
                target.queryParam(entry.getKey(), value);
            }
        }
        frontendVersionService.setNoStoreHeaders(response);
        response.sendRedirect(target.build().encode().toUriString());
    }

    @GetMapping(value = {"/mobile/", "/mobile/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    public void getMobileIndex(HttpServletRequest request, HttpServletResponse response) throws IOException {
        writeRenderedHtml("static/mobile/index.html", request.getContextPath(), response);
    }

    @GetMapping(value = "/mobile/html/{page}.html", produces = MediaType.TEXT_HTML_VALUE)
    public void getMobileHtmlPage(@PathVariable("page") String page,
                                  HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        if (!ALLOWED_MOBILE_HTML_PAGES.contains(page)) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        redirectToModulePage(page, true, request, response);
    }

    private void redirectToModulePage(String page,
                                      boolean mobile,
                                      HttpServletRequest request,
                                      HttpServletResponse response) throws IOException {
        UriComponentsBuilder target = UriComponentsBuilder.fromPath(request.getContextPath())
                .path(mobile ? "/mobile/index.html" : "/index.html")
                .queryParam("page", page);
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if ("page".equals(entry.getKey())) {
                continue;
            }
            for (String value : entry.getValue()) {
                target.queryParam(entry.getKey(), value);
            }
        }
        frontendVersionService.setNoStoreHeaders(response);
        response.sendRedirect(target.build().encode().toUriString());
    }

    private void writeRenderedHtml(String resourcePath,
                                   String contextPath,
                                   HttpServletResponse response) throws IOException {
        String content = frontendVersionService.readStaticText(resourcePath);
        if (content == null) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }

        response.setContentType("text/html;charset=UTF-8");
        frontendVersionService.setNoStoreHeaders(response);
        response.getWriter().write(frontendVersionService.renderHtml(content, contextPath));
    }
}
