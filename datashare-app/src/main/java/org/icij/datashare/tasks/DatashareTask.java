package org.icij.datashare.tasks;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;
import java.util.Date;
import java.util.Map;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskResult;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.user.User;

public class DatashareTask<V extends Serializable> extends Task<V> {
    protected volatile DatashareTask<V> result;

    public DatashareTask(String name, User user, Map<String, Object> args) {
        super(name, user, args);
    }

    public DatashareTask(String id, String name, User user) {
        super(id, name, user);
    }

    public DatashareTask(String id, String name, User user, Map<String, Object> args) {
        super(id, name, user, args);
    }

    public DatashareTask(
        String id, String name, State state, double progress, Date createdAt, int retriesLeft, Date completedAt,
        Map<String, Object> args, TaskResult<V> result, TaskError error
    ) {
        super(id, name, state, progress, createdAt, retriesLeft, completedAt, args, result, error);
    }

    // Define annotation for ser/de
    @Override
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, property = "@type")
    public void setArgs(Map<String, Object> args) {
        super.setArgs(args);
    }

    @Override
    public void setResult(TaskResult<V> result) {
        if (! (result instanceof DatashareTaskResult<V>)) {
            String msg = "expected result to be a " + DatashareTaskResult.class.getName() + " instance found " + result;
            throw new IllegalArgumentException(msg);
        }
        super.setResult(result);
    }
}
