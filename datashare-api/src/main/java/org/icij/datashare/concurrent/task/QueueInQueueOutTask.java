package org.icij.datashare.concurrent.task;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.concurrent.BooleanLatch;
import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.InputQueue;
import org.icij.datashare.concurrent.queue.OutputQueue;

import static java.util.Collections.singletonList;


/**
 * {@link Task} using {@code BlockingQueue}s for input and output
 *
 * Created by julien on 10/10/16.
 */
public abstract class QueueInQueueOutTask<I,O> extends AbstractTask implements InputQueue<I>, OutputQueue<O> {

    protected Logger LOGGER = LogManager.getLogger(getClass());


    protected final BlockingQueue<I> input;

    protected final BlockingQueue<O> output;

    private final List<Latch> noMoreInput;

    private final Latch noMoreOutput;


    public QueueInQueueOutTask(BlockingQueue<I> input, BlockingQueue<O> output, Latch noMoreInput) {
        this(input, output, singletonList(noMoreInput));
    }

    public QueueInQueueOutTask(BlockingQueue<I> input, BlockingQueue<O> output, List<Latch> noMoreInput) {
        this.input        = input;
        this.output       = output;
        this.noMoreInput  = noMoreInput;
        this.noMoreOutput = new BooleanLatch();
    }


    @Override
    public BlockingQueue<I> input() {
        return input;
    }

    @Override
    public List<Latch> noMoreInput() {
        return noMoreInput;
    }

    @Override
    public BlockingQueue<O> output() {
        return output;
    }

    @Override
    public Latch noMoreOutput() { return noMoreOutput; }


    @Override
    public final Result call() throws Exception {
        Result result = Result.FAILURE;
        if (initialize()) {
            while(  ! (input().isEmpty() && noMoreInput().stream().allMatch(Latch::isSignalled)) &&
                    ! Thread.currentThread().isInterrupted() ) {
                Optional<I> element = take();
                if (element.isPresent())
                    result = execute(element.get());
            }
        }
        terminate();
        return result;
    }

    protected boolean initialize() { return true; }

    protected abstract Result execute(I element);

    protected void terminate() { noMoreOutput.signal(); }

}
