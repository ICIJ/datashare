package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ErrorEvent extends TaskEvent {
    public final TaskError error;

    @JsonCreator
    public ErrorEvent(@JsonProperty("taskId") String taskId, @JsonProperty("error") TaskError error) {
        super(taskId);
        this.error= error;
    }
}
