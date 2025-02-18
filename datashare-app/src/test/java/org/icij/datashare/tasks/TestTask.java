package org.icij.datashare.tasks;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;

import static java.util.Optional.ofNullable;

@TaskGroup(TaskGroupType.Test)
public class TestTask implements CancellableTask, Callable<Integer> {
    private final int value;
    protected volatile Thread callThread = null;
    protected volatile Boolean requeue = null;
    private final CountDownLatch waitForTask = new CountDownLatch(1);

    public TestTask(int value) {
        this.value = value;
    }

    @Override
    public Integer call() throws Exception {
        waitForTask.countDown();
        ofNullable(this.requeue).ifPresent(CancelException::new);
        return value;
    }

    @Override
    public void cancel(boolean requeue) {
        this.requeue = requeue;
        ofNullable(callThread).ifPresent(Thread::interrupt);
    }
}
