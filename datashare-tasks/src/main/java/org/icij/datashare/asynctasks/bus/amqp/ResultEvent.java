package org.icij.datashare.asynctasks.bus.amqp;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.asynctasks.TaskResult;

import java.io.Serializable;

public class ResultEvent<V extends Serializable> extends TaskEvent {
    public final TaskResult<V> result;

    @JsonCreator
    public ResultEvent(@JsonProperty("taskId") String taskId, @JsonProperty("result") TaskResult<V> result) {
        super(taskId);
        this.result = result;
    }
}
