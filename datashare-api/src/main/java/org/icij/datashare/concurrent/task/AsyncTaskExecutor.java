package org.icij.datashare.concurrent.task;

import java.util.*;
import java.util.concurrent.*;


/**
 * Run {@link Task}s in parallel
 *
 * Created by julien on 8/9/16.
 */
public class AsyncTaskExecutor extends AbstractTaskExecutor {

    public AsyncTaskExecutor(List<? extends Task> tasks) {
        super(Executors.newFixedThreadPool(tasks.size()), tasks);
    }

}

