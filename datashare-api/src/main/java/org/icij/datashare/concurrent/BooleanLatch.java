package org.icij.datashare.concurrent;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * Created by julien on 10/17/16.
 */
public class BooleanLatch implements Latch {

    private static class Sync extends AbstractQueuedSynchronizer {
        boolean isSignalled() { return getState() != 0; }

        protected int tryAcquireShared(int ignore) {
            return isSignalled() ? 1 : -1;
        }

        protected boolean tryReleaseShared(int ignore) {
            setState(1);
            return true;
        }
    }


    private final Sync sync = new Sync();


    public boolean isSignalled() { return sync.isSignalled(); }

    public void signal() { sync.releaseShared(1); }

    public void await() throws InterruptedException { sync.acquireSharedInterruptibly(1); }

}
