package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CancelEvent extends Event {
    public final boolean requeue;
    public final String taskId;
    @JsonCreator
    public CancelEvent(@JsonProperty("taskId") String taskId, @JsonProperty("requeue") boolean requeue) {
        this.taskId = taskId;
        this.requeue = requeue;
    }
}
