package top.sshh.bililiverecoder.util.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRetryBackoffPolicyTest {

    @Test
    void shouldExposeRetryCategoryAndMessage() {
        UploadRetryBackoffPolicy policy = new UploadRetryBackoffPolicy();

        UploadRetryBackoffPolicy.BackoffDecision decision = policy.nextDecision(
                1,
                new RuntimeException("signed chunk upload failed, code=400, content=<Error><Code>UserNetworkTooSlow</Code></Error>"),
                null
        );

        assertTrue(decision.retryable());
        assertEquals(UploadRetryClassifier.REMOTE_THROTTLED, decision.retryCategory());
        assertEquals("平台限速，等待重试", decision.userMessage());
    }
}
