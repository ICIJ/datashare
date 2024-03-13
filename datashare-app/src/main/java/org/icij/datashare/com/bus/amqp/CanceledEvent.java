package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CanceledEvent extends CancelEvent {
    @JsonCreator
    public CanceledEvent(@JsonProperty("taskId") String taskId, @JsonProperty("requeue") boolean requeue) {
        super(taskId, requeue);
    }
}
