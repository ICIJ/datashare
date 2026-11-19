package org.icij.datashare.asynctasks.shutdownfixtures;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The first attempt blocks until it is interrupted, like a long running task being stopped by a worker shutdown. The
 * following attempts return immediately.
 */
public class InterruptibleTask implements Callable<String> {
    private final AtomicInteger attempts;
    private final CountDownLatch firstAttemptStarted;

    InterruptibleTask(AtomicInteger attempts, CountDownLatch firstAttemptStarted) {
        this.attempts = attempts;
        this.firstAttemptStarted = firstAttemptStarted;
    }

    @Override
    public String call() throws Exception {
        if (attempts.incrementAndGet() == 1) {
            firstAttemptStarted.countDown();
            Thread.sleep(60_000);
        }
        return "done";
    }
}
