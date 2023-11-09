package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.user.User;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskView<V> {
    final Map<String, Object> properties;

    public static TaskView<String> nullObject() {
        return new TaskView<>(null, State.INIT, 0, User.nullUser(), null, null);
    }

    public enum State {INIT, RUNNING, ERROR, DONE, CANCELLED}

    public final String id;

    public final User user;
    volatile String error;
    private volatile State state;
    private volatile double progress;
    private volatile V result;
    @JsonIgnore
    final MonitorableFutureTask<V> task;

    @Deprecated(since = "13.6.0")
    public TaskView(MonitorableFutureTask<V> task) {
        this.id = task.toString();
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
    TaskView(@JsonProperty("name") String id,
             @JsonProperty("state") State state,
             @JsonProperty("progress") double progress,
             @JsonProperty("user") User user,
             @JsonProperty("result") V result,
             @JsonProperty("properties") Map<String, Object> properties) {
        this.id = id;
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
                LoggerFactory.getLogger(getClass()).error(String.format("Task failed for user %s :", getUser()), e);
                error = e.getCause().toString();
                state = State.ERROR;
                return null;
            }
        } else {
            return result;
        }
    }

    public void setResult(V result) {
        this.result = result;
        this.state = State.DONE;
        this.progress = 1;
    }

    public void setProgress(double rate) {
        this.progress = rate;
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

    public boolean isNull() { return id == null;}
    public User getUser() { return user;}
    public static <V> String getId(Callable<V> task) {return task.toString();}
}