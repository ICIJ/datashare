package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.icij.datashare.user.User;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LanguageAgnosticTaskView<V> implements TaskViewInterface<V> {
    // TODO: add an ID ????
    final Map<String, Object> inputs;

    public final String name;

    public final User user;

    public final String type;
    volatile String error;
    private volatile State state;
    private volatile double progress;
    private volatile V result;

    public int retries;

    public int maxRetries;


    // TODO: we want the type type to be serialized as well

    @JsonCreator
    LanguageAgnosticTaskView(@JsonProperty("type") String type,
                             @JsonProperty("name") String name,
                             @JsonProperty("state") State state,
                             @JsonProperty("progress") double progress,
                             @JsonProperty("user") User user,
                             @JsonProperty("result") V result,
                             @JsonProperty("error") String error,
                             @JsonProperty("inputs") Map<String, Object> inputs,
                             @JsonProperty("retries") int retries,
                             @JsonProperty("maxRetries") int maxRetries
                             ) {
        this.type = type;
        this.name = name;
        this.state = state;
        this.progress = progress;
        this.user = user;
        this.result = result;
        this.error = error;
        this.inputs = inputs;
        this.retries = retries;
        this.maxRetries = maxRetries;
    }

    public LanguageAgnosticTaskView(String type, String name, User user,
                                    Map<String, Object> inputs) {
        this(type, name, State.CREATED, 0.0, user, null, null, inputs, 0, -1);
    }

    public void setState(State state) {
        this.state = state;
    }

    public void setResult(V result) {
        this.result = result;
    }

    public void setError(String error) {
        // TODO: add trace etc etc
        this.error = error;
    }

    public void setProgress(Double progress) {
        this.progress = progress;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }
    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }

    public static class LanguageAgnosticTaskViewUpdate<V> {
        public State state;
        public String name;
        public Double progress;
        public V result;
        public String error;
        public Integer retries;
        public Integer maxRetries;

        @JsonCreator
        public LanguageAgnosticTaskViewUpdate(@JsonProperty("name") String name,
                                              @JsonProperty("state") State state,
                                              @JsonProperty("progress") Double progress,
                                              @JsonProperty("result") V result,
                                              @JsonProperty("error") String error,
                                              @JsonProperty("retries") Integer retries,
                                              @JsonProperty("maxRetries") Integer maxRetries
                                              ) {
            // TODO: some states updates should probably be forbidden...
            this.name = name;
            this.state = state;
            this.progress = progress;
            this.result = result;
            this.error = error;
            this.retries = retries;
            this.maxRetries = maxRetries;
        }
        public LanguageAgnosticTaskViewUpdate(String name) {
            this(name, null, null, null, null, null, null);
        }
        public LanguageAgnosticTaskViewUpdate<V> withState(State state) {
            this.state = state;
            return this;
        }
        public LanguageAgnosticTaskViewUpdate<V> withResult(V res) {
            this.result = res;
            return this;
        }
        public LanguageAgnosticTaskViewUpdate<V> withError(String error) {
            this.error = error;
            return this;
        }

        public LanguageAgnosticTaskViewUpdate<V> withProgress(double progress) {
            this.progress = progress;
            return this;
        }
    }

    public LanguageAgnosticTaskViewUpdate<V> asUpdate() {
        return new LanguageAgnosticTaskViewUpdate<>(
            this.name,
            this.state,
            this.progress,
            this.result,
            this.error,
            this.retries,
            this.maxRetries
        );
    }

    @Override
    public V getResult() {
        return getResult(false);
    }

    @Override
    public V getResult(boolean sync) {
        return result;
    }

    @Override
    public double getProgress() {
        return progress;
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public String getError() {
        return this.error;
    }

    @Override
    public User getUser() {
        return user;
    }

    @Override
    public String getName() {
        return name;
    }
}