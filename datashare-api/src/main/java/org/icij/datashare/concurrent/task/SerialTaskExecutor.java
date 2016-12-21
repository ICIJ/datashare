package org.icij.datashare.concurrent.task;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * Run {@link Task}s in sequence
 *
 * Created by julien on 10/10/16.
 */
public class SerialTaskExecutor extends AbstractTaskExecutor {

    public SerialTaskExecutor(List<? extends Task> tasks) {
        super(Executors.newSingleThreadExecutor(), tasks);
    }

}
