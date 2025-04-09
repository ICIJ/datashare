package org.icij.datashare.tasks;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;

import static java.util.Optional.ofNullable;

@TaskGroup(TaskGroupType.Test)
public class SerializationTestTask implements CancellableTask, Callable<SerializationTestTask.UnknownResultType> {
    private final int value;
    protected volatile Thread callThread = null;
    protected volatile Boolean requeue = null;
    private final CountDownLatch waitForTask = new CountDownLatch(1);

    public SerializationTestTask(int value) {
        this.value = value;
    }

    // This represents any unknown result type defined outside of Datashare app, which should however be properly
    // handled by the app
    public record UnknownResultType(int value, Map<String, Object> whatever) {}

    @Override
    public UnknownResultType call() throws Exception {
        waitForTask.countDown();
        ofNullable(this.requeue).ifPresent(CancelException::new);

        return new UnknownResultType(value, Map.of("any", "extra"));
    }

    @Override
    public void cancel(boolean requeue) {
        this.requeue = requeue;
        ofNullable(callThread).ifPresent(Thread::interrupt);
    }
}
