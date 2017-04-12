package org.icij.datashare.concurrent.queue;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import org.apache.logging.log4j.LogManager;

import org.icij.datashare.concurrent.Latch;


/**
 * Created by julien on 3/10/17.
 */
public interface InputQueues<I> {

    List<BlockingQueue<I>> inputs();

    List<Latch> noMoreInputs();

    default Optional<I> poll(int timeout, TimeUnit unit) {
        long start = new Date().getTime();
        try {
            Optional<I> element;
            do {
                element = inputs().stream().map( BlockingQueue::poll ).filter( Objects::nonNull ).findAny();
                if ( ! element.isPresent())
                        Thread.sleep(100);
            } while ( ! element.isPresent() && (new Date().getTime() - start) < unit.toMillis(timeout));
            return element;
        } catch (InterruptedException e) {
            LogManager.getLogger(getClass()).info(getClass().getName() + " - QUEUE POLLING INTERRUPTED", e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    default Optional<I> poll() {
        return poll(120, SECONDS);
    }

}
