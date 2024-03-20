package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ProgressEvent extends TaskEvent {
    public final double rate;

    @JsonCreator
    public ProgressEvent(@JsonProperty("taskId") String taskId, @JsonProperty("rate") double rate) {
        super(taskId);
        this.rate = rate;
    }
}
