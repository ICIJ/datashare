package org.icij.datashare.concurrent;

/**
 * Created by julien on 10/17/16.
 */
public interface Latch {

    boolean isSignalled();

    void signal();

    void await() throws InterruptedException;

}
