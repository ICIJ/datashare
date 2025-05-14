package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.bus.amqp.UriResult;

import java.io.Serializable;

public enum TaskResultSubtypes {
    LONG(Long.class),
    URI_RESULT(UriResult.class),
    BATCH_SEARCH_RUNNER_RESULT(BatchSearchRunnerResult.class);

    private final Class<? extends Serializable> type;

    TaskResultSubtypes(Class<? extends Serializable> type) {
        this.type = type;
    }
}
