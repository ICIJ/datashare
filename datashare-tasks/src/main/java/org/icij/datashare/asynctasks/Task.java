package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;
import org.icij.datashare.user.User;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Task<V> extends Event implements Entity {
    public static final String USER_KEY = "user";
    public static final String GROUP_KEY = "group";
    @JsonIgnore private StateLatch stateLatch;
    @JsonIgnore private final Object lock = new Object();

    public enum State {CREATED, QUEUED, RUNNING, CANCELLED, ERROR, DONE;}
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
    public final Map<String, Object> args;
    public final String id;

    public final String name;
    volatile TaskError error;
    private volatile State state;
    private volatile double progress;
    @JsonSubTypes({
        @JsonSubTypes.Type(value = UriResult.class),
        @JsonSubTypes.Type(value = Long.class)
    })
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
    private volatile V result;

    public Task(String name, User user, Map<String, Object> args) {
        this(randomUUID().toString(), name, user, args);
    }

    public Task(String name, User user, Group group, Map<String, Object> args) {
        this(randomUUID().toString(), name, State.CREATED, 0, null, addTo(args, user, group));
    }

    public Task(String id, String name, User user, Group group) {
        this(id, name, user,addTo(new HashMap<>(), user, group));
    }

    public Task(String id, String name, User user, Map<String, Object> args) {
        this(id, name, State.CREATED, 0, null, addTo(args, user));
    }

    @JsonCreator
    Task(@JsonProperty("id") String id,
         @JsonProperty("name") String name,
         @JsonProperty("state") State state,
         @JsonProperty("progress") double progress,
         @JsonProperty("result") V result,
         @JsonProperty("args") Map<String, Object> args) {
        this.id = id;
        this.name = name;
        this.state = state;
        this.progress = progress;
        this.result = result;
        // avoids "no default constructor found" for anonymous inline maps
        this.args = Collections.unmodifiableMap(ofNullable(args).orElse(new HashMap<>()));
    }

    public V getResult() {
        return result;
    }

    /**
     * Beware that the lock is working only on a "local" use.
     * If the task is serialized/deserialized then the lock won't do anything
     * because the lock instance will change.
     *
     * @param timeout
     * @param unit
     * @return
     * @throws InterruptedException
     */
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
            setState(State.DONE);
            this.progress = 1;
            lock.notify();
        }
    }

    public void setError(TaskError reason) {
        synchronized (lock) {
            this.error = reason;
            setState(State.ERROR);
            this.progress = 1;
            lock.notify();
        }
    }

    public void cancel() {
        synchronized (lock) {
            setState(State.CANCELLED);
            lock.notify();
        }
    }

    public void setProgress(double rate) {
        synchronized (lock) {
            this.progress = rate;
            if (!State.RUNNING.equals(getState())) {
                setState(State.RUNNING);
            }
        }
    }

    public void queue() {
        synchronized (lock) {
            setState(State.QUEUED);
        }
    }

    public double getProgress() {
        return progress;
    }

    public TaskError getError() {
        return error;
    }

    public State getState() {
        return state;
    }

    @JsonIgnore
    public boolean isFinished() {
        return State.DONE.equals(state) || State.CANCELLED.equals(state) || State.ERROR.equals(state);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Task<?> taskView = (Task<?>) o;
        return Objects.equals(id, taskView.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + "@" + id;
    }

    @JsonIgnore
    public boolean isNull() {
        return id == null;
    }

    @JsonIgnore
    public User getUser() {
        return (User) args.get(USER_KEY);
    }

    @JsonIgnore
    public Group getGroup() {
        return (Group) args.get(GROUP_KEY);
    }

    public static <V> String getId(Callable<V> task) {
        return task.toString();
    }

    @Override
    public String getId() {
        return id;
    }

    public Function<Double, Void> progress(BiFunction<String, Double, Void> taskSupplierProgress) {
        return (p) -> taskSupplierProgress.apply(this.id, p);
    }

    void setLatch(StateLatch stateLatch) {
        this.stateLatch = stateLatch;
    }

    private void setState(State state) {
        this.state = state;
        ofNullable(stateLatch).ifPresent(sl -> sl.setTaskState(state));
    }

    private static Map<String, Object> addTo(Map<String, Object> properties, User user) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(properties);
        result.put(USER_KEY, user);
        return result;
    }

    private static Map<String, Object> addTo(Map<String, Object> properties, User user, Group group) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(properties);
        result.put(USER_KEY, user);
        result.put(GROUP_KEY, group);
        return result;
    }
}