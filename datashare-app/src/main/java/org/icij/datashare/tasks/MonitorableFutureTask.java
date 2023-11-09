package org.icij.datashare.tasks;

import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class MonitorableFutureTask<V> extends FutureTask<V> implements Monitorable, UserTask {
    private final Object callable;
    public final Map<String, Object> properties = new HashMap<>();

    public MonitorableFutureTask(Callable<V> callable) {
        super(callable);
        this.callable = callable;
    }

    public MonitorableFutureTask(Callable<V> task, Map<String, Object> properties) {
        super(task);
        this.properties.putAll(properties);
        callable = task;
    }

    private Monitorable getMonitorable(Object runnableOrCallable) {
        if (runnableOrCallable instanceof Monitorable) {
            return (Monitorable) runnableOrCallable;
        }
        return () -> -2;
    }

    @Override
    public double getProgressRate() {
        return getMonitorable(callable).getProgressRate();
    }

    @Override
    public String toString() { return callable.toString();}

    @Override
    public User getUser() {
        if (callable instanceof UserTask) {
            return ((UserTask) callable).getUser();
        }
        return User.local();
    }
}