package org.icij.datashare.concurrent.task;

import org.icij.datashare.concurrent.BooleanLatch;
import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.OutputQueue;

import java.util.List;
import java.util.concurrent.BlockingQueue;

import static java.util.Collections.singletonList;


/**
 * {@link Task} using {@code BlockingQueue}s for input and output
 *
 * Created by julien on 10/10/16.
 */
public abstract class QueueInQueueOutTask<I,O> extends QueuesInTask<I> implements OutputQueue<O> {

    private final BlockingQueue<O> output;

    private final Latch noMoreOutput;


    public QueueInQueueOutTask(List<BlockingQueue<I>> input, List<Latch> noMoreInput, BlockingQueue<O> output) {
        super(input, noMoreInput);
        this.output = output;
        this.noMoreOutput = new BooleanLatch();
    }

    public QueueInQueueOutTask(BlockingQueue<I> input, List<Latch> noMoreInput, BlockingQueue<O> output) {
        this(singletonList(input), noMoreInput, output);
    }

    public QueueInQueueOutTask(BlockingQueue<I> input, Latch noMoreInput, BlockingQueue<O> output) {
        this(singletonList(input), singletonList(noMoreInput), output);
    }

    public QueueInQueueOutTask(List<BlockingQueue<I>> input, Latch noMoreInput, BlockingQueue<O> output) {
        this(input, singletonList(noMoreInput), output);
    }


    @Override
    public BlockingQueue<O> output() {
        return output;
    }

    @Override
    public Latch noMoreOutput() { return noMoreOutput; }

    @Override
    protected void terminate() {
        LOGGER.info("TERMINATING");
        noMoreOutput.signal();
    }

}
