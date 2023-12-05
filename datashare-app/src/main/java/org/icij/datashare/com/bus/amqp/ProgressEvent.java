package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ProgressEvent extends Event {
    public final String taskId;
    public final double rate;

    @JsonCreator
    public ProgressEvent(@JsonProperty("taskId") String taskId, @JsonProperty("rate") double rate) {
        this.taskId = taskId;
        this.rate = rate;
    }
}
