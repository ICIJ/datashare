package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.bus.amqp.UriResult;

import java.io.Serializable;
import java.util.Arrays;

public enum TaskResultSubtypes {
    LONG(Long.class),
    URI_RESULT(UriResult.class),
    BATCH_SEARCH_RUNNER_RESULT(BatchSearchRunnerResult.class);

    private final Class<? extends Serializable> type;


    public Class<? extends Serializable> getType() {
        return type;
    }

    public static Class<? extends Serializable>[] getClasses() {
        return Arrays.stream(values()).map(TaskResultSubtypes::getType).toArray(Class[]::new);
    }

    TaskResultSubtypes(Class<? extends Serializable> type) {
        this.type = type;
    }
}
