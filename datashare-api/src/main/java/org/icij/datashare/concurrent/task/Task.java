package org.icij.datashare.concurrent.task;


import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.icij.datashare.util.reflect.EnumTypeToken;


/**
 * DataShare tasks
 *
 * {@code T} is an {@link EnumTypeToken} representing the underlying processing class
 *
 * Created by julien on 9/30/16.
 */
public interface Task extends Callable<Task.Result> {

    enum Result {
        SUCCESS,
        FAILURE;

    }

    void setFuture(Future future);

    Optional<Future> getFuture();

    boolean isDone();

}
