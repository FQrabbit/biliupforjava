package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sshh.bililiverecoder.service.FrontendVersionService;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HtmlPageControllerRedirectTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FrontendVersionService frontendVersionService = mock(FrontendVersionService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new HtmlPageController(frontendVersionService)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"stats", "history", "room"})
    void redirectsEveryDesktopModulePageAndPreservesQuery(String page) throws Exception {
        mockMvc.perform(get("/html/{page}.html", page).param("roomFilter", "live"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/index.html?page=" + page + "&roomFilter=live"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"stats", "history", "room"})
    void redirectsEveryMobileModulePageAndPreservesQuery(String page) throws Exception {
        mockMvc.perform(get("/mobile/html/{page}.html", page).param("roomFilter", "recording"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/mobile/index.html?page=" + page + "&roomFilter=recording"));
    }

    @Test
    void redirectsMobileDirectoryWithoutTrailingSlashAndPreservesQuery() throws Exception {
        mockMvc.perform(get("/mobile")
                        .param("page", "stats")
                        .param("source", "bookmark")
                        .param("tag", "one", "two"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/mobile/?page=stats&source=bookmark&tag=one&tag=two"));
    }

    @Test
    void legacyRedirectUsesRoutePageAndPreservesContextPath() throws Exception {
        mockMvc.perform(get("/biliup/html/stats.html")
                        .contextPath("/biliup")
                        .param("page", "room")
                        .param("filter", "a b"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/biliup/index.html?page=stats&filter=a%20b"));
    }

    @Test
    void mobileDirectoryRedirectPreservesContextPath() throws Exception {
        mockMvc.perform(get("/biliup/mobile")
                        .contextPath("/biliup")
                        .param("page", "history"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/biliup/mobile/?page=history"));
    }

    @Test
    void rejectsUnknownLegacyPage() throws Exception {
        mockMvc.perform(get("/html/not-a-page.html"))
                .andExpect(status().isNotFound());
    }
}
