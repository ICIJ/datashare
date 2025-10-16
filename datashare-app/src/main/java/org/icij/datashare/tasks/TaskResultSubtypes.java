package org.icij.datashare.tasks;

import com.fasterxml.jackson.databind.jsontype.NamedType;
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

    public static NamedType[] getClasses() {
        return Arrays.stream(values()).map(type -> new NamedType(type.getType())).toArray(NamedType[]::new);
    }

    TaskResultSubtypes(Class<? extends Serializable> type) {
        this.type = type;
    }
}
