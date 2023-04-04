package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.extract.MemoryBlockingQueue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class RemoteTaskManager implements TaskManager {
    final BlockingQueue<TaskView<?>> queue;
    final Map<String, TaskView<?>> tasks = new LinkedHashMap<>();
    @Inject
    public RemoteTaskManager(PropertiesProvider propertiesProvider) {
        this.queue = new MemoryBlockingQueue<>(propertiesProvider, "tasks");
    }

    @Override
    public TaskView<Void> startTask(Runnable task) {
        return null;
    }
    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Runnable callback) {
        return null;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task, Map<String, Object> properties) {
        TaskView<V> taskView = new TaskView<>(new MonitorableFutureTask<V>(task, properties));
        save(taskView);
        queue.add(taskView);
        return taskView;
    }

    @Override
    public <V> TaskView<V> startTask(Callable<V> task) {
        return null;
    }

    @Override
    public TaskView<?> takeTask() throws InterruptedException {
        return queue.poll(2, TimeUnit.SECONDS);
    }

    @Override
    public boolean stopTask(String taskName) {
        return false;
    }

    @Override
    public <V> TaskView<?> clearTask(String taskName) {
        return null;
    }

    @Override
    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        return false;
    }

    @Override
    public <V> Void save(TaskView<V> task) {
        tasks.put(task.name, task);
        return null;
    }

    @Override
    public TaskView<?> get(String id) {
        return null;
    }

    @Override
    public List<TaskView<?>> get() {
        return new ArrayList<>(tasks.values());
    }

    @Override
    public List<TaskView<?>> clearDoneTasks() {
        return null;
    }
}
