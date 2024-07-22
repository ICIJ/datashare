package org.icij.datashare.asynctasks;

import org.junit.After;
import org.junit.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.asynctasks.Task.State.CREATED;
import static org.icij.datashare.asynctasks.Task.State.RUNNING;

public class StateLatchTest {
    private static final int WAIT_MS = 50;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    public void test_status_latch_await_with_timeout() throws InterruptedException {
        StateLatch stateLatch = new StateLatch(CREATED);
        long start = System.currentTimeMillis();
        executor.submit((Callable<Void>) () -> {
            Thread.sleep(WAIT_MS);
            stateLatch.setTaskState(Task.State.RUNNING);
            return null;
        });
        assertThat(stateLatch.await(Task.State.RUNNING, 1500, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(WAIT_MS);
    }

    @Test
    public void test_status_latch_await() throws InterruptedException {
        StateLatch stateLatch = new StateLatch(CREATED);
        long start = System.currentTimeMillis();
        executor.submit((Callable<Void>) () -> {
            Thread.sleep(WAIT_MS);
            stateLatch.setTaskState(Task.State.RUNNING);
            return null;
        });
        stateLatch.await(Task.State.RUNNING);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(WAIT_MS);
    }

    @Test
    public void test_get_state() {
        StateLatch stateLatch = new StateLatch(CREATED);
        assertThat(stateLatch.getTaskState()).isEqualTo(CREATED);
        stateLatch.setTaskState(RUNNING);
        assertThat(stateLatch.getTaskState()).isEqualTo(RUNNING);
    }

    @After
    public void tearDown() throws Exception {
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }
}