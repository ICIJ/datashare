package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CancelledEvent extends CancelEvent {
    @JsonCreator
    public CancelledEvent(@JsonProperty("taskId") String taskId, @JsonProperty("requeue") boolean requeue) {
        super(taskId, requeue);
    }
}
