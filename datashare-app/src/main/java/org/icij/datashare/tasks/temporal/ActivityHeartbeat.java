package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityExecutionContext;
import io.temporal.client.ActivityCompletionException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Beats in the background while an activity runs, so that a stage needs no change to be seen alive by the server (a
 * worker that dies is noticed after the heartbeat timeout, not after the start-to-close one) and to learn that its
 * workflow was cancelled: the server answers a beat with that, and the thread of the activity is then interrupted,
 * which is what {@code StageContext.isCancelled()} reads. It tells the worker is alive, not that the stage makes
 * progress: a stage that hangs is stopped by the start-to-close timeout.
 */
final class ActivityHeartbeat implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ActivityHeartbeat.class);
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger heartbeatNumber = new AtomicInteger(0);

    /** To be called from the thread of the activity: it is the one interrupted on a cancellation. */
    static ActivityHeartbeat start(ActivityExecutionContext activity) {
        return new ActivityHeartbeat(activity, activity.getInfo().getHeartbeatTimeout(), Thread.currentThread());
    }

    ActivityHeartbeat(ActivityExecutionContext activity, Duration timeout, Thread worker) {
        if (timeout == null || timeout.isZero()) {
            scheduler = null;
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "activity-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        // three beats per timeout: one can be lost or late without the attempt being given away
        long periodMs = Math.max(1, timeout.toMillis() / 3);
        scheduler.scheduleAtFixedRate(() -> beat(activity, worker), periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    private void beat(ActivityExecutionContext activity, Thread worker) {
        try {
            activity.heartbeat(heartbeatNumber.getAndIncrement());
        } catch (ActivityCompletionException cancellation) {
            // cancelled, timed out, or the worker is shutting down: nothing more to beat for
            logger.info("activity stopped by the server ({}), interrupting the stage",
                        cancellation.getClass().getSimpleName());
            cancelled.set(true);
            worker.interrupt();
            scheduler.shutdown();
        } catch (RuntimeException e) {
            // a failing beat must not end the others: an exception would stop a scheduled task for good
            logger.warn("heartbeat failed, will try again", e);
        }
    }

    @Override
    public void close() {
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (cancelled.get()) {
            // our own interrupt must not leak into the next activity that runs on this pooled thread
            Thread.interrupted();
        }
    }
}
