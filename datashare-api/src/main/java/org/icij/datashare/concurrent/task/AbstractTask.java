package org.icij.datashare.concurrent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.Future;


/**
 * Common structure and behavior for tasks
 *
 * Created by julien on 9/30/16.
 */
public abstract class AbstractTask implements Task {

    protected  final Logger LOGGER = LoggerFactory.getLogger(getClass());

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
