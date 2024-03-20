package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.icij.datashare.Entity;
import org.icij.datashare.user.User;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;
import static org.icij.datashare.tasks.TaskView.State.CANCELLED;
import static org.icij.datashare.tasks.TaskView.State.DONE;
import static org.icij.datashare.tasks.TaskView.State.ERROR;
import static org.icij.datashare.tasks.TaskView.State.RUNNING;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskView<V> implements Entity {

    public enum State {CREATED, QUEUED, RUNNING, ERROR, DONE, CANCELLED}
    public final Map<String, Object> properties;

    public final String id;
    public final String name;
    public final User user;
    volatile Throwable error;
    private volatile State state;
    private volatile double progress;
    private volatile V result;
    @JsonIgnore
    private final Object lock = new Object();

    public TaskView(String name, User user, Map<String, Object> properties) {
        this(randomUUID().toString(), name, user, properties);
    }
    public TaskView(String id, String name, User user) {
        this(id, name, user, new HashMap<>());
    }
    public TaskView(String id, String name, User user, Map<String, Object> properties) {
        this(id, name, State.CREATED, 0, user, null, properties);
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
    }

    public V getResult() {
        return result;
    }

    public V getResult(int timeout, TimeUnit unit) throws InterruptedException {
        synchronized (lock) {
            if (!isFinished()) {
                lock.wait(unit.toMillis(timeout));
            }
            return result;
        }
    }

    public void setResult(Serializable result) {
        synchronized (lock) {
            this.result = (V) result;
            this.state = State.DONE;
            this.progress = 1;
            lock.notify();
        }
    }

    public void setError(Throwable reason) {
        synchronized (lock) {
            this.error = reason;
            this.state = State.ERROR;
            this.progress = 1;
            lock.notify();
        }
    }

    public void cancel() {
        synchronized (lock) {
            state = State.CANCELLED;
            lock.notify();
        }
    }

    public void setProgress(double rate) {
        synchronized (lock) {
            this.progress = rate;
            if (! RUNNING.equals(state)) {
                this.state = RUNNING;
            }
        }
    }

    public void queue() {
        synchronized (lock) {
            state = State.QUEUED;
        }
    }

    public double getProgress() {
        return progress;
    }

    public State getState() {
        return state;
    }

    @JsonIgnore
    public boolean isFinished() {
        return DONE.equals(state) || CANCELLED.equals(state) || ERROR.equals(state);
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
    public String toString() {return name + "@" + id;}
    @JsonIgnore
    public boolean isNull() { return id == null;}
    public User getUser() { return user;}
    public static <V> String getId(@NotNull Callable<V> task) {return task.toString();}
    @Override
    public String getId() {return id;}
    public static TaskView<Serializable> nullObject() {
        return new TaskView<>(null, null, State.CREATED, 0, User.nullUser(), null, new HashMap<>());
    }
}