package org.icij.datashare.concurrent.queue;

import org.icij.datashare.concurrent.Latch;

import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;


/**
 * Created by julien on 3/10/17.
 */
public interface InputQueues<I> {

    List<BlockingQueue<I>> inputs();

    List<Latch> noMoreInputs();

    default Optional<I> poll(int timeout, TimeUnit unit) {
        long start = new Date().getTime();
        Optional<I> element;
        do {
            element = inputs().stream().map(BlockingQueue::poll).filter(Objects::nonNull).findAny();

        } while (!element.isPresent() && (new Date().getTime() - start) < unit.toMillis(timeout));
        return element;

    }

    default Optional<I> poll() {
        return poll(120, SECONDS);
    }

}
