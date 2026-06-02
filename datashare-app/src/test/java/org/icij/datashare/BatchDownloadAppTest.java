package org.icij.datashare;

import org.junit.Test;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.fest.assertions.Assertions.assertThat;

public class BatchDownloadAppTest {
    @Test
    public void test_cleaner_runs_immediately_when_scheduled() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        ScheduledExecutorService scheduler = BatchDownloadApp.scheduleCleanup(callCount::incrementAndGet);
        Thread.sleep(100);
        scheduler.shutdown();
        assertThat(callCount.get()).isGreaterThan(0);
    }
}
