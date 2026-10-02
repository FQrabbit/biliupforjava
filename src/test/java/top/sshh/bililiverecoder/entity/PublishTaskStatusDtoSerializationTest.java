package top.sshh.bililiverecoder.entity;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishTaskStatusDtoSerializationTest {
    @Test
    void recordingAndMergeReasonsAreSerializedAsActualWaitingStates() throws Exception {
        PublishTaskStatusDto dto = new PublishTaskStatusDto();
        dto.setState(PublishTaskState.WAITING_UPLOAD);
        dto.setWaitReason("RECORDING");
        assertEquals("等待录制结束", objectMapper.readTree(objectMapper.writeValueAsString(dto)).get("label").asText());
        dto.setWaitReason("MERGE_INTERVAL");
        assertEquals("等待合并", objectMapper.readTree(JSON.toJSONString(dto)).get("label").asText());
        RecordHistory history = new RecordHistory();
        history.setPublishWaitReason("MERGE_INTERVAL");
        history.setPublishNotBefore(java.time.LocalDateTime.of(2026, 10, 2, 4, 20));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        assertEquals("MERGE_INTERVAL", mapper.readTree(mapper.writeValueAsString(history)).get("publishWaitReason").asText());
        assertTrue(mapper.readTree(mapper.writeValueAsString(history)).hasNonNull("publishNotBefore"));
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesNestedTaskStatesForJacksonAndFastjson() throws Exception {
        PublishTaskStatusDto status = new PublishTaskStatusDto(
                41L, 52L, 63L, PublishTaskOperation.EDIT_PARTS,
                PublishTaskState.WAITING_CAPTCHA, "PUBLISH_CAPTCHA",
                "等待完成此稿件的验证码", 2, 3, null, null, null, null);
        status.setCaptchaRetryCount(2);

        JsonNode jackson = objectMapper.readTree(objectMapper.writeValueAsString(
                java.util.Map.of("accepted", true, "status", status)));
        assertTrue(jackson.get("accepted").asBoolean());
        assertEquals("WAITING_CAPTCHA", jackson.at("/status/state").asText());
        assertEquals("EDIT_PARTS", jackson.at("/status/operation").asText());
        assertEquals("等待验证码", jackson.at("/status/label").asText());
        assertEquals(2, jackson.at("/status/captchaRetryCount").asInt());
        assertEquals(3, jackson.at("/status/captchaRetryLimit").asInt());

        JsonNode fastjson = objectMapper.readTree(JSON.toJSONString(
                java.util.Map.of("accepted", true, "status", status)));
        assertTrue(fastjson.get("accepted").asBoolean());
        assertEquals(41L, fastjson.at("/status/taskId").asLong());
        assertEquals("CAPTCHA", fastjson.at("/status/code").asText());
        assertEquals(2, fastjson.at("/status/captchaRetryCount").asInt());
    }
}
