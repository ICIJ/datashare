package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import java.io.Serializable;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;


public class DatashareTaskResult<V extends Serializable> extends TaskResult<V> {
    public DatashareTaskResult(
        @JsonSubTypes({
            @JsonSubTypes.Type(value = UriResult.class),
            @JsonSubTypes.Type(value = Long.class)
        })
        V value
    ) {
        super(value);
    }
}