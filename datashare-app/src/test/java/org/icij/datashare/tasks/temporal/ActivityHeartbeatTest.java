package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCanceledException;
import java.time.Duration;
import static org.fest.assertions.Assertions.assertThat;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

public class ActivityHeartbeatTest {
    private final ActivityExecutionContext activity = mock(ActivityExecutionContext.class);

    @Test
    public void test_it_beats_while_the_activity_runs() {
        try (ActivityHeartbeat ignored = new ActivityHeartbeat(activity, Duration.ofMillis(90), Thread.currentThread())) {
            verify(activity, timeout(2000).atLeast(3)).heartbeat(any());
        }
    }

    @Test
    public void test_it_stops_beating_when_closed() throws Exception {
        new ActivityHeartbeat(activity, Duration.ofMillis(60), Thread.currentThread()).close();
        clearInvocations(activity);
        Thread.sleep(200);

        verify(activity, never()).heartbeat(any());
    }

    @Test
    public void test_a_zero_timeout_does_not_beat() throws Exception {
        try (ActivityHeartbeat ignored = new ActivityHeartbeat(activity, Duration.ZERO, Thread.currentThread())) {
            Thread.sleep(100);
        }

        verify(activity, never()).heartbeat(any());
    }

    @Test
    public void test_a_cancellation_interrupts_the_thread_of_the_activity_and_does_not_leak_after_close() {
        doThrow(mock(ActivityCanceledException.class)).when(activity).heartbeat(any());

        try (ActivityHeartbeat ignored = new ActivityHeartbeat(activity, Duration.ofMillis(90), Thread.currentThread())) {
            assertThrows(InterruptedException.class, () -> Thread.sleep(5000));
        }

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    public void test_a_failing_beat_does_not_end_the_others() {
        doThrow(new IllegalStateException("network")).when(activity).heartbeat(any());

        try (ActivityHeartbeat ignored = new ActivityHeartbeat(activity, Duration.ofMillis(90), Thread.currentThread())) {
            verify(activity, timeout(2000).atLeast(3)).heartbeat(any());
        }
    }
}
