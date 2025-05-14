package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Date;

public abstract class TaskEvent extends Event {
    public final String taskId;

    @JsonCreator
    public TaskEvent(@JsonProperty("taskId") String taskId) {
        this.taskId = taskId;
    }

    protected TaskEvent(Date creationDate, int ttl, String taskId) {
        super(creationDate, ttl);
        this.taskId = taskId;
    }
}
