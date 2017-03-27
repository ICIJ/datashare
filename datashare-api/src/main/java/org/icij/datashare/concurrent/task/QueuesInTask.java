package org.icij.datashare.concurrent.task;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import static java.util.Collections.singletonList;

import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.queue.InputQueues;


/**
 * Created by julien on 3/10/17.
 */
public abstract class QueuesInTask<I> extends AbstractTask implements InputQueues<I> {

    protected final List<BlockingQueue<I>> inputs;

    private final List<Latch> noMoreInputs;


    public QueuesInTask(List<BlockingQueue<I>> inputs, List<Latch> noMoreInputs) {
        super();
        this.inputs       = inputs;
        this.noMoreInputs = noMoreInputs;
    }

    public QueuesInTask(BlockingQueue<I> input, List<Latch> noMoreInputs) {
        this(singletonList(input), noMoreInputs);
    }


    @Override
    public List<BlockingQueue<I>> inputs() {
        return inputs;
    }

    @Override
    public List<Latch> noMoreInputs() {
        return noMoreInputs;
    }


    @Override
    public Result call() throws Exception {
        Result result = Result.FAILURE;
        if (initialize()) {
            while(  ! (inputs().stream().allMatch(BlockingQueue::isEmpty) && noMoreInputs().stream().allMatch(Latch::isSignalled)) &&
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
