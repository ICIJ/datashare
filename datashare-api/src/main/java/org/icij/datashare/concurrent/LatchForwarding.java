package org.icij.datashare.concurrent;

import com.hazelcast.core.ICountDownLatch;

import org.icij.datashare.concurrent.queue.OutputQueue;
import org.icij.datashare.concurrent.task.AbstractTask;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


/**
 * Latch Forwarding Task, with timeout
 * {@link Way#DOWNWARDS}: Await and Forward global Latch to local Latch
 * {@link Way#UPWARDS}: Await and Forward local Latch to global Latch
 *
 * Created by julien on 1/19/17.
 */
public class LatchForwarding extends AbstractTask {

    public enum Way {
        DOWNWARDS,
        UPWARDS;

        public static Optional<LatchForwarding.Way> parse(final String way) {
            if (way== null || way.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(way.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    public static final long     DEFAULT_TIMEOUT      = 7;
    public static final TimeUnit DEFAULT_TIMEOUT_UNIT = TimeUnit.DAYS;

    private final Latch           localLatch;
    private final ICountDownLatch globalLatch;
    private final Way             forwardingWay;
    private final long            timeout;
    private final TimeUnit        timeoutUnit;


    public LatchForwarding(Latch localLatch,
                           ICountDownLatch globalLatch,
                           Way forwardingWay,
                           long timeout,
                           TimeUnit timeoutUnit) {
        this.localLatch    = localLatch;
        this.globalLatch   = globalLatch;
        this.forwardingWay = forwardingWay;
        this.timeout       = timeout;
        this.timeoutUnit   = timeoutUnit;
    }

    public LatchForwarding(Latch fromLocal, ICountDownLatch toGlobal) {
        this(fromLocal, toGlobal, Way.UPWARDS, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT);
    }

    public LatchForwarding(OutputQueue outputQueue, ICountDownLatch toGlobal) {
        this(outputQueue.noMoreOutput(), toGlobal, Way.UPWARDS, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT);
    }

    public LatchForwarding(ICountDownLatch fromGlobal, Latch toLocal) {
        this(toLocal, fromGlobal, Way.DOWNWARDS, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT_UNIT);
    }


    @Override
    public Result call() throws Exception {
        try {
            if (forwardingWay.equals(Way.UPWARDS)) {
                localLatch.awaitFor(timeout, timeoutUnit);
                globalLatch.countDown();
            } else {
                globalLatch.await(timeout, timeoutUnit);
                localLatch.signal();
            }
            return Result.SUCCESS;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILURE;
        }
    }

}
