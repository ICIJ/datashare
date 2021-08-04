package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.user.User;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskView<V> {
    private final Map<String, Object> properties;
    public enum State {RUNNING, ERROR, DONE, CANCELLED}
    public final String name;
    public final State state;
    public final double progress;
    public final User user;
    private V result;

    public TaskView(MonitorableFutureTask<V> task) {
        this.name = task.toString();
        this.user = task.getUser();
        State taskState;
        if (task.isDone()) {
            try {
                result = task.get();
                taskState = State.DONE;
            } catch (CancellationException cex) {
                taskState = State.CANCELLED;
                result = null;
            } catch (ExecutionException |InterruptedException e) {
                taskState = State.ERROR;
                result = null;
            }
            progress = 1;
            state = task.isCancelled() ? State.CANCELLED : taskState;
        } else {
            result = null;
            state = State.RUNNING;
            progress = task.getProgressRate();
        }
        this.properties = task.properties.isEmpty() ? null: task.properties;
    }

    @JsonCreator
    private TaskView(@JsonProperty("name") String name,
                     @JsonProperty("state") State state,
                     @JsonProperty("progress") double progress,
                     @JsonProperty("user") User user,
                     @JsonProperty("result") V result,
                     @JsonProperty("properties") Map<String, Object> properties) {
        this.name = name;
        this.state = state;
        this.progress = progress;
        this.user = user;
        this.result = result;
        this.properties = properties;
    }

    public V getResult() {
        return result;
    }
    public User getUser() { return user;}
}