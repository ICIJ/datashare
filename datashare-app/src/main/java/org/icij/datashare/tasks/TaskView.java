package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.client.ResponseException;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.user.User;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static java.util.Arrays.stream;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskView<V> {
    final Map<String, Object> properties;

    public enum State {RUNNING, ERROR, DONE, CANCELLED;}

    public final String name;

    public final User user;
    volatile Exception error;
    private volatile State state;
    private volatile double progress;
    private volatile V result;
    @JsonIgnore
    final MonitorableFutureTask<V> task;
    public TaskView(MonitorableFutureTask<V> task) {
        this.name = task.toString();
        this.user = task.getUser();
        this.properties = task.properties.isEmpty() ? null: task.properties;
        this.task = task;
        if (task.isDone()) {
            this.result = getResult();
        } else {
            result = null;
            state = State.RUNNING;
            progress = task.getProgressRate();
        }
    }
    @JsonCreator
    TaskView(@JsonProperty("name") String name,
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
        this.task = null;
    }

    public V getResult() {
        return getResult(false);
    }

    public V getResult(boolean sync) {
        if (task != null && (task.isDone() || sync)) {
            try {
                progress = 1;
                state = State.DONE;
                return task.get();
            } catch (CancellationException cex) {
                state = State.CANCELLED;
                return null;
            } catch (ExecutionException | InterruptedException e) {
                if (e.getCause() instanceof ElasticsearchStatusException) { // can't be deserialized by jackson
                    error = new SearchException("unknown", stream(e.getSuppressed()).filter(t -> t instanceof ResponseException).findFirst().orElse(e));
                } else {
                    error = e;
                }
                state = State.ERROR;
                return null;
            }
        } else {
            return result;
        }
    }

    public double getProgress() {
        if (task != null) {
            return task.isDone() ? 1 : task.getProgressRate();
        }
        return progress;
    }

    public State getState() {
        if (task != null) {
            if (!task.isDone()) {
                return State.RUNNING;
            }
            getResult();
        }
        return state;
    }

    public User getUser() { return user;}
}