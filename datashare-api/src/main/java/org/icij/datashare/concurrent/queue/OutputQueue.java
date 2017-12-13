package org.icij.datashare.concurrent.queue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.icij.datashare.concurrent.Latch;

import java.util.concurrent.BlockingQueue;


/**
 * Outputs come from {@code BlockingQueue<O>}
 *
 * Created by julien on 10/17/16.
 */
public interface OutputQueue<O> {

    BlockingQueue<O> output();

    Latch noMoreOutput();

    default void put(O element) {
        Log LOGGER =  LogFactory.getLog(getClass());
        LOGGER.debug("putting " + element + " ["+output().size()+"]" );
        try {
            output().put(element);
        } catch (InterruptedException e) {
            LOGGER.info("putting on queue interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

}
