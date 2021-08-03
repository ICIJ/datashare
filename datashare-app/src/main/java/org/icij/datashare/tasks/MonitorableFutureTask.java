package org.icij.datashare.tasks;

import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

public class MonitorableFutureTask<V> extends FutureTask<V> implements Monitorable, UserTask {
    private final Object runnableOrCallable;
    public final Map<String, Object> properties = new HashMap<>();

    public MonitorableFutureTask(Callable<V> callable) {
        super(callable);
        runnableOrCallable = callable;
    }

    public MonitorableFutureTask(Runnable runnable, V result) {
        super(runnable, result);
        runnableOrCallable = runnable;
    }

    public MonitorableFutureTask(Callable<V> task, Map<String, Object> properties) {
        super(task);
        this.properties.putAll(properties);
        runnableOrCallable = task;
    }

    private Monitorable getMonitorable(Object runnableOrCallable) {
        if (runnableOrCallable instanceof Monitorable) {
            return (Monitorable) runnableOrCallable;
        }
        return () -> -2;
    }

    @Override
    public double getProgressRate() {
        return getMonitorable(runnableOrCallable).getProgressRate();
    }

    @Override
    public String toString() { return runnableOrCallable.toString();}

    @Override
    public User getUser() {
        if (runnableOrCallable instanceof UserTask) {
            return ((UserTask) runnableOrCallable).getUser();
        }
        return User.local();
    }
}