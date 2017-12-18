package org.icij.datashare.concurrent.queue;

import org.icij.datashare.concurrent.Latch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;


/**
 * Inputs come from {@code BlockingQueue<I>}
 *
 * Created by julien on 10/17/16.
 */
public interface InputQueue<I> {

    BlockingQueue<I> input();

    List<Latch> noMoreInput();

    default Optional<I> poll(int timeout, TimeUnit unit) {
        final Logger LOGGER = LoggerFactory.getLogger(getClass());
        LOGGER.debug("polling " + input() + ", [" + input().size() + "]");
        try {
            I inputElement = input().poll(timeout, unit);
            return Optional.ofNullable( inputElement );
        } catch (InterruptedException e) {
            LOGGER.info("queue polling interrupted", e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    default Optional<I> poll() {
        return poll(120, SECONDS);
    }

}
