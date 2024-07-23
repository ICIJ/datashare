package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

public class ResultEvent<V extends Serializable> extends TaskEvent {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    @JsonSubTypes({@JsonSubTypes.Type(TaskError.class), @JsonSubTypes.Type(UriResult.class), @JsonSubTypes.Type(Long.class)})
    public final V result;

    @JsonCreator
    public ResultEvent(@JsonProperty("taskId") String taskId, @JsonProperty("result") V result) {
        super(taskId);
        this.result = result;
    }
}
