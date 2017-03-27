package org.icij.datashare.concurrent;

import java.util.concurrent.locks.AbstractQueuedSynchronizer;

/**
 * Created by julien on 10/17/16.
 */
public class BooleanLatch extends IntegerLatch {

    public BooleanLatch() { super(1); }

}
