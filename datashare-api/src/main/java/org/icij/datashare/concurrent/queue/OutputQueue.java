package org.icij.datashare.concurrent.queue;

import java.util.concurrent.BlockingQueue;

import org.apache.logging.log4j.LogManager;

import org.icij.datashare.concurrent.Latch;


/**
 * Outputs are put on a {@code BlockingQueue<O>}
 *
 * Created by julien on 10/17/16.
 */
public interface OutputQueue<O> {

    BlockingQueue<O> output();

    Latch noMoreOutput();

    default void put(O element) {
        LogManager.getLogger(getClass())
                .debug(getClass().getName() + " - " + "Putting element " + element + "on queue " + output() );
        try {
            output().put(element);

        } catch (InterruptedException e) {
            LogManager.getLogger(OutputQueue.class)
                    .info(getClass().getName() + " - " + "Putting element on queue interrupted",  e);
            Thread.currentThread().interrupt();
        }
    }

}
