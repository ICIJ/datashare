package org.icij.datashare.asynctasks.shutdownfixtures;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskFactory;

public class InterruptibleTaskFactory implements TaskFactory {
    public final AtomicInteger attempts = new AtomicInteger();
    public final CountDownLatch firstAttemptStarted = new CountDownLatch(1);

    public InterruptibleTask createInterruptibleTask(Task<String> task, Function<Double, String> progress) {
        return new InterruptibleTask(attempts, firstAttemptStarted);
    }
}
