package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public abstract class TaskEvent extends Event {
    public final String taskId;

    @JsonCreator
    public TaskEvent(@JsonProperty("taskId") String taskId) {
        this.taskId = taskId;
    }
}
