package org.icij.datashare.tasks;

import org.icij.concurrent.SealableLatch;

import java.util.function.BooleanSupplier;

/**
 * A SealableLatch that is sealed once the input queue is drained, meaning empty with a terminal
 * producer task. It lets DocumentQueueDrainer keep polling a temporarily empty queue while the
 * producer is still enqueuing, and stop as soon as the producer is done and the queue is empty.
 */
class UpstreamSealableLatch implements SealableLatch {
    private final BooleanSupplier drained;
    private final long pollIntervalMs;
    private volatile boolean sealed = false;

    UpstreamSealableLatch(BooleanSupplier drained, long pollIntervalMs) {
        this.drained = drained;
        this.pollIntervalMs = pollIntervalMs;
    }

    /** Nothing to wake up: the drainer re-polls on its own once {@link #await()} returns. */
    @Override
    public void signal() {}

    @Override
    public void await() throws InterruptedException {
        Thread.sleep(pollIntervalMs);
    }

    @Override
    public void seal() {
        sealed = true;
    }

    @Override
    public boolean isSealed() {
        return sealed || drained.getAsBoolean();
    }
}
