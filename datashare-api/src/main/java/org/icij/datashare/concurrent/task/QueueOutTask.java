package org.icij.datashare.concurrent.task;

import java.util.concurrent.BlockingQueue;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.BooleanLatch;
import org.icij.datashare.concurrent.queue.OutputQueue;


/**
 * An {@link AbstractTask} writing outputs to a {@link BlockingQueue}
 *
 * Created by julien on 1/6/17.
 */
public abstract class QueueOutTask<O> extends AbstractTask implements OutputQueue<O> {

    private final BlockingQueue<O> output;

    private Latch noMoreOutput;


    public QueueOutTask(BlockingQueue<O> output) {
        super();
        this.output = output;
        this.noMoreOutput = new BooleanLatch();
    }


    @Override
    public BlockingQueue<O> output() {
        return output;
    }

    @Override
    public Latch noMoreOutput() {
        return noMoreOutput;
    }

}
