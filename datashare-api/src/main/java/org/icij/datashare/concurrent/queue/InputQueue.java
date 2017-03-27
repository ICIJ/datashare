package org.icij.datashare.concurrent.queue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;

import org.icij.datashare.concurrent.Latch;

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
        LogManager.getLogger(getClass()).debug(getClass().getName() + " - POLLING " + input() + ", [" + input().size() + "]");
        try {
            I inputElement = input().poll(timeout, unit);
            return Optional.ofNullable( inputElement );
        } catch (InterruptedException e) {
            LogManager.getLogger(getClass()).info("Queue polling interrupted", e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    default Optional<I> poll() {
        return poll(120, SECONDS);
    }

}
