package org.icij.datashare.concurrent;

import java.util.concurrent.TimeUnit;

/**
 * Created by julien on 10/17/16.
 */
public interface Latch {

    boolean isSignalled();

    void signal();

    void await() throws InterruptedException;

    void awaitFor(long timeout, TimeUnit unit) throws InterruptedException;

}
