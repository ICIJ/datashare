package org.icij.datashare.asynctasks;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.TaskView.State.CREATED;
import static org.icij.datashare.asynctasks.TaskView.State.RUNNING;

public class StatusLatchTest  {
    private static final int WAIT_MS = 50;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_status_latch_await_with_timeout() throws InterruptedException {
        StatusLatch statusLatch = new StatusLatch(CREATED);
        long start = System.currentTimeMillis();
        executor.submit((Callable<Void>) () -> {
            Thread.sleep(WAIT_MS);
            statusLatch.setTaskState(TaskView.State.RUNNING);
            return null;
        });
        assertThat(statusLatch.await(TaskView.State.RUNNING, 1500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(System.currentTimeMillis() - start).isGreaterThan(WAIT_MS);
    }

    @Test
    public void test_status_latch_await() throws InterruptedException {
        StatusLatch statusLatch = new StatusLatch(CREATED);
        long start = System.currentTimeMillis();
        executor.submit((Callable<Void>) () -> {
            Thread.sleep(WAIT_MS);
            statusLatch.setTaskState(TaskView.State.RUNNING);
            return null;
        });
        statusLatch.await(TaskView.State.RUNNING);
        assertThat(System.currentTimeMillis() - start).isGreaterThan(WAIT_MS);
    }

    @Test
    public void test_get_state() {
        StatusLatch statusLatch = new StatusLatch(CREATED);
        assertThat(statusLatch.getTaskState()).isEqualTo(CREATED);
        statusLatch.setTaskState(RUNNING);
        assertThat(statusLatch.getTaskState()).isEqualTo(RUNNING);
    }

    @After
    public void tearDown() throws Exception {
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }
}