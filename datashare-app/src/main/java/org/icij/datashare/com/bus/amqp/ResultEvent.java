package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public class ResultEvent<V extends Serializable> extends Event {
    public final V result;
    public final String taskId;

    @JsonCreator
    public ResultEvent(@JsonProperty("taskId") String taskId, @JsonProperty("result") V result) {
        this.taskId = taskId;
        this.result = result;
    }
}
