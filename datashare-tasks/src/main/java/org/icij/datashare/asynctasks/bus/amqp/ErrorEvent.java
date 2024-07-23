package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

public class ErrorEvent extends TaskEvent {
    public final TaskError error;

    @JsonCreator
    public ErrorEvent(@JsonProperty("taskId") String taskId, @JsonProperty("error") TaskError error) {
        super(taskId);
        this.error= error;
    }
}
