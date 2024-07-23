package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ProgressEvent extends TaskEvent {
    public final double progress;

    @JsonCreator
    public ProgressEvent(@JsonProperty("taskId") String taskId, @JsonProperty("progress") double progress) {
        super(taskId);
        this.progress = progress;
    }
}
