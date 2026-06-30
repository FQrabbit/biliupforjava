package top.sshh.bililiverecoder.util.retry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadRetryClassifierTest {

    @Test
    void shouldClassifyUserNetworkTooSlowAsRetryable() {
        UploadRetryClassifier.UploadRetryAssessment assessment = UploadRetryClassifier.assess(
                new RuntimeException("signed chunk upload failed, code=400, content=<Error><Code>UserNetworkTooSlow</Code></Error>"),
                null
        );

        assertTrue(assessment.retryable());
        assertEquals(UploadRetryClassifier.REMOTE_THROTTLED, assessment.category());
        assertEquals("平台限速，等待重试", assessment.userMessage());
    }

    @Test
    void shouldTreatAuthErrorAsNotRetryable() {
        UploadRetryClassifier.UploadRetryAssessment assessment = UploadRetryClassifier.assess(
                new RuntimeException("signed chunk upload failed, code=403, content=forbidden"),
                null
        );

        assertTrue(!assessment.retryable());
        assertEquals(UploadRetryClassifier.AUTH, assessment.category());
    }
}
