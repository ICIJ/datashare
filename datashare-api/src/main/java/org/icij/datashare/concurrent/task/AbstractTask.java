package org.icij.datashare.concurrent.task;

import java.util.Optional;
import java.util.concurrent.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Common structure and behavior for tasks
 *
 * Created by julien on 9/30/16.
 */
public abstract class AbstractTask implements Task {

    protected final Logger LOGGER = LogManager.getLogger(getClass());

    volatile private Future future;


    protected AbstractTask() {}


    @Override
    public void setFuture(Future future) {
        this.future = future;
    }

    @Override
    public Optional<Future> getFuture() {
        return Optional.ofNullable(future);
    }

    @Override
    public boolean isDone() {
        return  ! getFuture().isPresent() || future.isDone();
    }

}
