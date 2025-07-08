    package org.icij.datashare.asynctasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.builder.CompareToBuilder;
import org.icij.datashare.Entity;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.batch.WebQueryPagination;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;

import java.util.Collections;
import java.util.Date;
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
import static org.icij.datashare.batch.WebQueryPagination.OrderDirection.ASC;
import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Task extends Event implements Entity, Comparable<Task>{
    public static final String USER_KEY = "user";
    @JsonIgnore private StateLatch stateLatch;
    @JsonIgnore private final Object lock = new Object();

    public enum State {
        CREATED, QUEUED, RUNNING, CANCELLED, ERROR, DONE;

        public static final Set<State> FINAL_STATES = Set.of(CANCELLED, ERROR, DONE);
        public static final Set<State> NON_FINAL_STATES = Arrays.stream(State.values()).filter(s -> !FINAL_STATES.contains(s))
            .collect(Collectors.toSet());
    }
    public final Map<String, Object> args;
    public final String id;
    public final String name;
    volatile TaskError error;
    private volatile State state;
    private volatile Date completedAt;
    private volatile double progress;
    private volatile byte[] result;

    public Task(String name, User user, Map<String, Object> args) {
        this(randomUUID().toString(), name, user, args);
    }

    public Task(String id, String name, User user) {
        this(id, name, user, addTo(new HashMap<>(), user));
    }

    public Task(String id, String name, User user, Map<String, Object> args) {
        this(id, name, State.CREATED, 0, DatashareTime.getNow(), MAX_RETRIES_LEFT, null, addTo(args, user), null, null);
    }

    @JsonCreator
    public Task(@JsonProperty("id") String id,
         @JsonProperty("name") String name,
         @JsonProperty("state") State state,
         @JsonProperty("progress") double progress,
         @JsonProperty("createdAt") Date createdAt,
         @JsonProperty("retriesLeft") int retriesLeft,
         @JsonProperty("completedAt") Date completedAt,
         @JsonProperty("args") Map<String, Object> args,
         @JsonProperty("result") byte[] result,
         @JsonProperty("error") TaskError error) {
        super(createdAt, retriesLeft);
        this.id = id;
        this.name = name;
        this.state = state;
        this.progress = progress;
        this.completedAt = completedAt;
        this.result = result;
        this.error = error;
        // avoids "no default constructor found" for anonymous inline maps
        this.args = Collections.unmodifiableMap(ofNullable(args).orElse(new HashMap<>()));
    }

    public byte[] getResult() {
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
    public byte[] getResult(int timeout, TimeUnit unit) throws InterruptedException {
        synchronized (lock) {
            if (!isFinished()) {
                lock.wait(unit.toMillis(timeout));
            }
            return result;
        }
    }

    public void setState(State state) {
        this.state = state;
        ofNullable(stateLatch).ifPresent(sl -> sl.setTaskState(state));
    }

    public void setResult(byte[] result) {
        synchronized (lock) {
            this.result =  result;
            setState(State.DONE);
            this.progress = 1;
            this.completedAt = DatashareTime.getNow();
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

    public int getRetriesLeft() {return retriesLeft;}
    public Date getCompletedAt() {return completedAt;}

    @JsonIgnore
    public boolean isFinished() {
        return State.FINAL_STATES.contains(state);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Task task = (Task) o;
        return Double.compare(progress, task.progress) == 0 &&
                Objects.equals(id, task.id) &&
                Objects.equals(name, task.name)
                && state == task.state &&
                Objects.equals(createdAt, task.createdAt) &&
                Objects.equals(completedAt, task.completedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, state, completedAt, createdAt, progress);
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
        // Ugly hack to avoid serializing the user with Java type info... We could have remove the user from the task
        // and move it to datashare task however, it's useful to keep it to index task by user
        // (faster task retrieval/filtering in the taskRepo)
        try {
            return MAPPER.readValue(MAPPER.writeValueAsBytes(args.get(USER_KEY)), User.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
    public int compareTo(Task task) {
        return new Comparator("name", ASC).compare(this, task);
    }

    public record Comparator(String field, WebQueryPagination.OrderDirection order) implements java.util.Comparator<Task> {
        public static Map<String, Function<Task, ?>> SORT_FIELDS = Map.of(
            "id", Task::getId,
            "user", Task::getUser,
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
        public int compare(Task t1, Task t2) {
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

    private static Map<String, Object> addTo(Map<String, Object> properties, User user) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(properties);
        result.put(USER_KEY, user);
        return result;
    }
}