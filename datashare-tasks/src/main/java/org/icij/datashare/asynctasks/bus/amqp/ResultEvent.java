package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class ResultEvent extends TaskEvent {
    public final byte[] result;

    @JsonCreator
    public ResultEvent(@JsonProperty("taskId") String taskId, @JsonProperty("result") byte[] result) {
        super(taskId);
        this.result = result;
    }
}
