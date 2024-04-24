package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CancelEvent extends TaskEvent {
    public final boolean requeue;
    @JsonCreator
    public CancelEvent(@JsonProperty("taskId") String taskId, @JsonProperty("requeue") boolean requeue) {
        super(taskId);
        this.requeue = requeue;
    }
}
