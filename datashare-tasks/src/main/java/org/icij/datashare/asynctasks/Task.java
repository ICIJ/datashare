package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.asynctasks.bus.amqp.UriResult;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.icij.datashare.batch.WebQueryPagination;

import static java.util.Optional.ofNullable;
import static java.util.UUID.randomUUID;
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.ASC;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Task<V> extends Event implements Entity, Comparable<Task<V>>{
    @JsonIgnore private StateLatch stateLatch;
    @JsonIgnore private final Object lock = new Object();

    public enum State {CREATED, QUEUED, RUNNING, CANCELLED, ERROR, DONE}
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

    public Task(String name, Map<String, Object> args) {
        this(randomUUID().toString(), name, State.CREATED, 0, null, args);
    }

    public Task(String id, String name) {
        this(id, name, new HashMap<>());
    }

    public Task(String id, String name, Map<String, Object> args) {
        this(id, name, State.CREATED, 0, null, args);
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

    @Override
    public int compareTo(Task<V> task) {
        return new Comparator("name", ASC).compare(this, task);
    }

    public record Comparator(String field, WebQueryPagination.OrderDirection order) implements java.util.Comparator<Task<?>> {
        public static Map<String, Function<Task<?>, ?>> SORT_FIELDS = Map.of(
                "id", Task::getId,
                "createdAt", t -> t.createdAt,
                "name", t -> t.name,
                "state", Task::getState,
                "finished", Task::isFinished
        );

        public Comparator(String field) {this(field, ASC);}
        public Comparator(String field, WebQueryPagination.OrderDirection order) {
            this.field = ofNullable(SORT_FIELDS.get(field)).map(f -> field)
                    .orElseThrow(() -> new IllegalArgumentException("no sort field with name " + field));
            this.order = order;
        }

        @Override
        public int compare(Task<?> t1, Task<?> t2) {
            CompareToBuilder compareToBuilder = new CompareToBuilder();
            Object fieldValue1 = SORT_FIELDS.get(field()).apply(t1);
            Object fieldValue2 = SORT_FIELDS.get(field()).apply(t2);
            compareToBuilder = order == ASC ?
                    compareToBuilder.append(fieldValue1, fieldValue2):
                    compareToBuilder.append(fieldValue2, fieldValue1);
            return compareToBuilder.toComparison();
        }
    }

    void setLatch(StateLatch stateLatch) {
        this.stateLatch = stateLatch;
    }

    private void setState(State state) {
        this.state = state;
        ofNullable(stateLatch).ifPresent(sl -> sl.setTaskState(state));
    }
}