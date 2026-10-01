package top.sshh.bililiverecoder.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import top.sshh.bililiverecoder.config.MvcConfig;
import top.sshh.bililiverecoder.service.DatabaseMaintenanceService;
import top.sshh.bililiverecoder.service.RecordWebhookInboxService;
import top.sshh.bililiverecoder.service.WebhookRequestGuard;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebhookMvcCompatibilityTest {
    private static final String BASIC_AUTH = "Basic dXNlcjpwYXNzd29yZA==";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RecordWebhookInboxService inbox = mock(RecordWebhookInboxService.class);
        DatabaseMaintenanceService maintenance = mock(DatabaseMaintenanceService.class);
        when(inbox.accept(anyString(), anyString(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(new RecordWebhookInboxService.Acceptance(1L, false, "PENDING"));
        when(inbox.recent(org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());

        RecordWebHook recordWebhook = new RecordWebHook(inbox, maintenance, new WebhookRequestGuard(""));
        BlrecWebhookController blrecWebhook = new BlrecWebhookController(maintenance, inbox,
                new WebhookRequestGuard(""));
        RecordWebhookInboxController inboxController = new RecordWebhookInboxController(inbox);

        MvcConfig config = new MvcConfig(mock(AsyncTaskExecutor.class));
        ReflectionTestUtils.setField(config, "userName", "user");
        ReflectionTestUtils.setField(config, "password", "password");
        InspectableInterceptorRegistry registry = new InspectableInterceptorRegistry();
        config.addInterceptors(registry);

        mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(recordWebhook, blrecWebhook, inboxController)
                .addInterceptors((MappedInterceptor) registry.registeredInterceptors().get(0))
                .build();
    }

    @Test
    void bothLegacyWebhookEndpointsPassTheConfiguredMvcLoginInterceptor() throws Exception {
        mockMvc.perform(post("/recordWebHook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"EventId\":\"evt-1\",\"EventType\":\"FileClosed\",\"EventData\":{\"RoomId\":\"1\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        mockMvc.perform(post("/webhook/blrec")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt-2\",\"type\":\"FileClosed\",\"data\":{\"room_id\":\"1\"}}"))
                .andExpect(status().isOk())
                .andExpect(content().string(""));
    }

    @Test
    void webhookManagementEndpointStillRequiresLogin() throws Exception {
        mockMvc.perform(get("/recordWebHook/inbox"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/recordWebHook/inbox").header("Authorization", BASIC_AUTH))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    private static final class InspectableInterceptorRegistry extends InterceptorRegistry {
        private List<Object> registeredInterceptors() {
            return getInterceptors();
        }
    }
}
