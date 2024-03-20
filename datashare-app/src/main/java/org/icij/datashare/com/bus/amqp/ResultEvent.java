package org.icij.datashare.com.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

public class ResultEvent<V extends Serializable> extends TaskEvent {
    public final V result;

    @JsonCreator
    public ResultEvent(@JsonProperty("taskId") String taskId, @JsonProperty("result") V result) {
        super(taskId);
        this.result = result;
    }
}
