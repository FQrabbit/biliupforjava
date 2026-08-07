package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.service.DiagnosticExportService;
import top.sshh.bililiverecoder.service.DiagnosticExportProgressService;
import top.sshh.bililiverecoder.service.LogArchiveService;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DiagnosticControllerTest {

    private final DiagnosticExportService exportService = mock(DiagnosticExportService.class);
    private final LogArchiveService logArchiveService = mock(LogArchiveService.class);
    private final DiagnosticExportProgressService progressService = mock(DiagnosticExportProgressService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new DiagnosticController(exportService, logArchiveService, progressService)).build();
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
        when(progressService.resolveExportId(any())).thenReturn("00000000-0000-4000-8000-000000000001");
        when(progressService.reporter(any())).thenReturn(DiagnosticExportProgressService.ProgressReporter.NOOP);
        doAnswer(invocation -> {
            OutputStream outputStream = invocation.getArgument(1);
            outputStream.write(zipBytes);
            return null;
        }).when(exportService).write(same(plan), any(OutputStream.class), any());

        MvcResult streaming = mockMvc.perform(post("/diagnostics/export")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(streaming))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("X-Diagnostic-Export-Id", "00000000-0000-4000-8000-000000000001"))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(content().bytes(zipBytes));
        verify(exportService).release();
        verify(progressService).complete("00000000-0000-4000-8000-000000000001");
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

    @Test
    void progressAndCancelEndpointsExposeTaskState() throws Exception {
        String exportId = "00000000-0000-4000-8000-000000000003";
        Map<String, Object> state = Map.of("exportId", exportId, "state", "RUNNING", "percent", 42);
        when(progressService.status(exportId)).thenReturn(state);
        when(progressService.cancel(exportId)).thenReturn(Map.of("exportId", exportId, "state", "CANCELLED"));

        mockMvc.perform(get("/diagnostics/exports/{exportId}/progress", exportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.percent").value(42));
        mockMvc.perform(post("/diagnostics/exports/{exportId}/cancel", exportId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }
}
