package cn.nexus.trigger.job.user;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.user.adapter.port.IUserEventOutboxPort;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class UserEventOutboxRetryJobTest {

    @Test
    void retryPending_shouldDelegateToOutboxPort() {
        IUserEventOutboxPort outboxPort = Mockito.mock(IUserEventOutboxPort.class);
        UserEventOutboxRetryJob job = new UserEventOutboxRetryJob(outboxPort);

        job.retryPending();

        verify(outboxPort).tryPublishPending();
    }

    @Test
    void retryPending_shouldSwallowException() {
        IUserEventOutboxPort outboxPort = Mockito.mock(IUserEventOutboxPort.class);
        UserEventOutboxRetryJob job = new UserEventOutboxRetryJob(outboxPort);
        Mockito.doThrow(new RuntimeException("boom")).when(outboxPort).tryPublishPending();

        job.retryPending();

        verify(outboxPort).tryPublishPending();
    }

    @Test
    void cleanDone_shouldPassSevenDaysAgoBoundary() {
        IUserEventOutboxPort outboxPort = Mockito.mock(IUserEventOutboxPort.class);
        UserEventOutboxRetryJob job = new UserEventOutboxRetryJob(outboxPort);
        when(outboxPort.cleanDoneBefore(Mockito.any(Date.class))).thenReturn(3);
        long beforeCall = System.currentTimeMillis();

        job.cleanDone();

        long afterCall = System.currentTimeMillis();
        ArgumentCaptor<Date> captor = ArgumentCaptor.forClass(Date.class);
        verify(outboxPort).cleanDoneBefore(captor.capture());
        long actual = captor.getValue().getTime();
        long lower = beforeCall - 7L * 24 * 3600 * 1000 - 2000L;
        long upper = afterCall - 7L * 24 * 3600 * 1000 + 2000L;
        assertTrue(actual >= lower && actual <= upper);
    }
}
