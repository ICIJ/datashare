package org.icij.datashare.tasks;

import java.util.concurrent.CountDownLatch;

import static java.util.Optional.ofNullable;

public class TestTask implements CancellableCallable<Integer> {
    private final int value;
    protected volatile Thread callThread = null;
    protected volatile String cancelTaskId = null;
    private final CountDownLatch waitForTask = new CountDownLatch(1);

    public TestTask(int value) {
        this.value = value;
    }

    @Override
    public Integer call() throws Exception {
        waitForTask.countDown();
        ofNullable(cancelTaskId).ifPresent(CancelException::new);
        return value;
    }

    @Override
    public void cancel(String taskId, boolean requeue) {
        cancelTaskId = taskId;
        ofNullable(callThread).ifPresent(Thread::interrupt);
    }

    public void awaitToBeStarted() throws InterruptedException {
        waitForTask.await();
    }
}
