package org.icij.datashare.concurrent;

import java.util.concurrent.CountDownLatch;

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

}
