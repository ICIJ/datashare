package org.icij.datashare.concurrent;

import java.sql.Time;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Created by julien on 10/17/16.
 */
public class IntegerLatch extends CountDownLatch implements Latch {

    /**
     * Constructs a {@code CountDownLatch} initialized with the given count.
     *
     * @param count the number of times {@link #countDown} must be invoked
     *              before threads can pass through {@link #await}
     * @throws IllegalArgumentException if {@code count} is negative
     */
    public IntegerLatch(int count) {
        super(count);
    }


    public void signal() { countDown(); }

    public boolean isSignalled() { return getCount() == 0; }

    @Override
    public void awaitFor(long timeout, TimeUnit unit) throws InterruptedException {
        await(timeout, unit);
    }

}
