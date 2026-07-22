package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.service.DiagnosticExportService;
import top.sshh.bililiverecoder.service.LogArchiveService;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiagnosticControllerTest {

    private final DiagnosticExportService exportService = mock(DiagnosticExportService.class);
    private final LogArchiveService logArchiveService = mock(LogArchiveService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DiagnosticController(exportService, logArchiveService)).build();
    }

    @Test
    void exportUsesStreamingResponseBodyHandler() throws Exception {
        DiagnosticExportRequest request = new DiagnosticExportRequest();
        DiagnosticExportService.ExportPlan plan = new DiagnosticExportService.ExportPlan(
                request, null, List.of(), null, List.of(), List.of(), null, null, null);
        byte[] zipBytes = "test-zip".getBytes(StandardCharsets.UTF_8);
        when(exportService.prepare(any(DiagnosticExportRequest.class))).thenReturn(plan);
        when(exportService.tryAcquire()).thenReturn(true);
        when(exportService.filename(plan)).thenReturn("biliupforjava-diagnostics.zip");
        doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(1);
            outputStream.write(zipBytes);
            return null;
        }).when(exportService).write(same(plan), any(OutputStream.class));

        MvcResult streaming = mockMvc.perform(post("/diagnostics/export")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(zipBytes));
        verify(exportService).release();
    }

    @Test
    void exportValidationErrorsRemainJson() throws Exception {
        when(exportService.prepare(any(DiagnosticExportRequest.class)))
                .thenThrow(new IllegalArgumentException("日志范围无效"));

        mockMvc.perform(post("/diagnostics/export")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.message").value("日志范围无效"));
    }
}
