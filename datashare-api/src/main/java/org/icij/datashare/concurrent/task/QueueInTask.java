package org.icij.datashare.concurrent.task;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import static java.util.Collections.singletonList;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.InputQueue;


/**
 * An {@link AbstractTask} reading inputs from a {@link BlockingQueue}
 * until it's empty and signalled as drained for good
 *
 * Created by julien on 1/5/17.
 */
public abstract class QueueInTask<I> extends AbstractTask implements InputQueue<I> {

    protected final BlockingQueue<I> input;

    private final List<Latch> noMoreInput;
    

    public QueueInTask(BlockingQueue<I> input, List<Latch> noMoreInput) {
        super();
        this.input       = input;
        this.noMoreInput = noMoreInput;
    }

    public QueueInTask(BlockingQueue<I> input, Latch noMoreInput) {
        this(input, singletonList(noMoreInput));
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
    public final Result call() throws Exception {
        Result result = Result.FAILURE;
        if (initialize()) {
            while(  ! (input().isEmpty() && noMoreInput().stream().allMatch(Latch::isSignalled)) &&
                    ! Thread.currentThread().isInterrupted() ) {
                Optional<I> element = poll();
                if (element.isPresent())
                    result = process(element.get());
            }
        }
        terminate();
        return result;
    }

    protected abstract boolean initialize();

    protected abstract Result process(I element);

    protected abstract void terminate();

}
