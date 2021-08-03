package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskView<V> {
    private final Map<String, Object> properties;

    enum State {RUNNING, ERROR, DONE, CANCELLED}
    private final String name;
    private final State state;
    private final double progress;
    private V result;

    public TaskView(MonitorableFutureTask<V> task) {
        this.name = task.toString();
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

    public V getResult() {
        return result;
    }
}