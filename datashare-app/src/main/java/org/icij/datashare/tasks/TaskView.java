package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.Entity;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskView<V> implements Entity {
    public enum State {INIT, RUNNING, ERROR, DONE, CANCELLED}
    public final Map<String, Object> properties;

    public final String id;
    public final String name;
    public final User user;
    volatile Throwable error;
    private volatile State state;
    private volatile double progress;
    private volatile V result;
    @JsonIgnore
    final MonitorableFutureTask<V> task;

    @Deprecated(since = "13.6.0")
    public TaskView(MonitorableFutureTask<V> task) {
        this.id = task.toString();
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

    public TaskView(String name, User user, Map<String, Object> properties) {
        this(randomUUID().toString(), name, State.INIT, 0, user, null, properties);
    }

    @JsonCreator
    TaskView(@JsonProperty("id") String id,
             @JsonProperty("name") String name,
             @JsonProperty("state") State state,
             @JsonProperty("progress") double progress,
             @JsonProperty("user") User user,
             @JsonProperty("result") V result,
             @JsonProperty("properties") Map<String, Object> properties) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.progress = progress;
        this.user = user;
        this.result = result;
        // avoids "no default constructor found" for anonymous inline maps
        this.properties = Collections.unmodifiableMap(ofNullable(properties).orElse(new HashMap<>()));
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
                error = e;
                state = State.ERROR;
                return null;
            }
        } else {
            return result;
        }
    }

    public void setResult(Serializable result) {
        this.result = (V) result;
        this.state = State.DONE;
        this.progress = 1;
    }

    public void setError(Throwable reason) {
        this.error = reason;
        this.state = State.ERROR;
        this.progress = 1;
    }

    public void setProgress(double rate) {
        this.progress = rate;
        if (rate > 0 && State.INIT.equals(state)) {
            this.state = State.RUNNING;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TaskView<?> taskView = (TaskView<?>) o;
        return Objects.equals(id, taskView.id);
    }
    @Override
    public int hashCode() {return Objects.hash(id);}
    @Override
    public String toString() {return getClass().getSimpleName() + "@" + id;}
    @JsonIgnore
    public boolean isNull() { return id == null;}
    public User getUser() { return user;}
    public static <V> String getId(@NotNull Callable<V> task) {return task.toString();}
    @Override
    public String getId() {return id;}
    public static TaskView<Serializable> nullObject() {
        return new TaskView<>(null, null, State.INIT, 0, User.nullUser(), null, new HashMap<>());
    }
}