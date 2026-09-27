package top.sshh.bililiverecoder.entity;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishTaskStatusDtoSerializationTest {

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
