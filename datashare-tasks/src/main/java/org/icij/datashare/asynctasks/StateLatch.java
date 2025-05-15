package org.icij.datashare.asynctasks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;

public class StateLatch {
    private final Sync sync;

    public StateLatch() {
        this.sync = new StateLatch.Sync(Task.State.CREATED);
    }

    public StateLatch(Task.State state) {
        this.sync = new StateLatch.Sync(state);
    }

    public void await(Task.State state) throws InterruptedException {
        this.sync.acquireSharedInterruptibly(state.ordinal());
    }

    public boolean  await(Task.State state, long timeout, TimeUnit unit) throws InterruptedException {
        return this.sync.tryAcquireSharedNanos(state.ordinal(), unit.toNanos(timeout));
    }

    public void setTaskState(Task.State state) {
        this.sync.releaseShared(state.ordinal());
    }

    public Task.State getTaskState() {
        return Task.State.values()[sync.getOrdinal()];
    }

    public String toString() {
        return super.toString() + "[State = " + this.sync.getOriginalTaskState() + "]";
    }

    private static final class Sync extends AbstractQueuedSynchronizer {
        private static final long serialVersionUID = 4982264981922014374L;
        private final Task.State originalTaskState;

        Sync(Task.State state) {
            this.setState(state.ordinal());
            this.originalTaskState = state;
        }

        Task.State getOriginalTaskState() { return this.originalTaskState;}
        int getOrdinal() { return this.getState();}

        @Override
        protected int tryAcquireShared(int acquires) {
            return this.getState() == acquires ? 1 : -1;
        }

        protected boolean tryReleaseShared(int askedStatusOrdinal) {
            int s;
            do {
                s = getState();
                if (s == askedStatusOrdinal) {
                    return false;
                }
            } while (!this.compareAndSetState(s, askedStatusOrdinal));
            return askedStatusOrdinal == getState();
        }
    }
}