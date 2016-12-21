package org.icij.datashare.concurrent.queue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;

import org.icij.datashare.concurrent.Latch;


/**
 * Inputs are taken from a {@code BlockingQueue<I>}
 *
 * Created by julien on 10/17/16.
 */
public interface InputQueue<I> {

    BlockingQueue<I> input();

    List<Latch> noMoreInput();

    default Optional<I> take() {
        LogManager.getLogger(getClass()).debug(getClass().getName() +
                " - " + "Polling queue "+ input() + ", [" + input().size() + "] remaining");
        try {
            I inputElement = input().poll(30, TimeUnit.SECONDS);
            return Optional.ofNullable( inputElement );

        } catch (InterruptedException e) {
            LogManager.getLogger(InputQueue.class).info("Queue polling interrupted", e);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

}
