package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.repo.BiliUserRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishAccountCooldownServiceTest {
    @Test
    void riskBackoffPersistsPerAccountAndResetsAfterSuccess() {
        BiliUserRepository repository = mock(BiliUserRepository.class);
        BiliBiliUser a = new BiliBiliUser();
        a.setId(1L);
        BiliBiliUser b = new BiliBiliUser();
        b.setId(2L);
        when(repository.findById(1L)).thenReturn(Optional.of(a));
        when(repository.findById(2L)).thenReturn(Optional.of(b));
        PublishAccountCooldownService service = new PublishAccountCooldownService(repository, "5,15,30,60");

        service.recordRisk(1L);
        assertEquals(1, a.getPublishRiskFailures());
        assertTrue(minutesRemaining(a) >= 4);
        service.recordRisk(1L);
        assertEquals(2, a.getPublishRiskFailures());
        assertTrue(minutesRemaining(a) >= 14);
        assertNull(b.getPublishCooldownUntil());

        PublishAccountCooldownService afterRestart = new PublishAccountCooldownService(repository, "5,15,30,60");
        assertTrue(afterRestart.waitMs(1L) >= 14 * 60_000L);
        assertEquals(0L, afterRestart.waitMs(2L));
        a.setPublishLastRiskAt(LocalDateTime.now().minusMinutes(11));
        afterRestart.recordSuccess(1L);
        assertEquals(2, a.getPublishRiskFailures());
        afterRestart.recordSuccess(1L);
        assertEquals(2, a.getPublishRiskFailures());
        afterRestart.recordSuccess(1L);
        assertEquals(0L, afterRestart.waitMs(1L));
        assertEquals(0, a.getPublishRiskFailures());
        assertNull(a.getPublishLastRiskAt());
        verify(repository, atLeast(3)).save(a);
    }

    private static long minutesRemaining(BiliBiliUser user) {
        return Duration.between(LocalDateTime.now(), user.getPublishCooldownUntil()).toMinutes();
    }
}
