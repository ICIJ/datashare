package org.icij.datashare.tasks;

import com.google.inject.Inject;
import net.codestory.http.security.User;
import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import static java.lang.Integer.parseInt;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

public class TaskManagerMemory implements TaskManager, TaskSupplier {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final ConcurrentMap<String, TaskView<?>> tasks = new ConcurrentHashMap<>();

    @Inject
    public TaskManagerMemory(final PropertiesProvider provider) {
        Optional<String> parallelism = provider.get("parallelism");
        executor = parallelism.map(s -> newFixedThreadPool(parseInt(s))).
                   orElseGet( () -> newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    @Override
    public <V> TaskView<V> startTask(final Callable<V> task, final Runnable callback) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<>(task) {
            @Override
            protected void done() {
                callback.run();
            }
        };
        TaskView<V> taskView = new TaskView<>(futureTask);
        executor.submit(futureTask);
        save(taskView);
        return taskView;
    }

    @Override
    public <V> TaskView<V> startTask(final Callable<V> task, Map<String, Object> properties) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<V>(task, properties);
        TaskView<V> taskView = new TaskView<>(futureTask);
        executor.submit(futureTask);
        save(taskView);
        return taskView;
    }

    @Override
    public <V> TaskView<V> startTask(final Callable<V> task) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<>(task);
        TaskView<V> taskView = new TaskView<>(futureTask);
        executor.submit(futureTask);
        save(taskView);
        return taskView;
    }

    public <V> TaskView<V> getTask(final String taskId) {
        return (TaskView<V>) tasks.get(taskId);
    }

    @Override
    public List<TaskView<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<TaskView<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
    }

    @Override
    public Void progress(String taskId, double rate) {
        tasks.get(taskId).setProgress(rate);
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        getTask(taskId).setResult(result);
    }

    @Override
    public void error(String taskId, Throwable reason) {
        throw new NotImplementedException("TODO");
    }

    private void save(TaskView<?> taskView) {
        tasks.put(taskView.id, taskView);
    }

    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        executor.shutdown();
        return executor.awaitTermination(timeout, timeUnit);
    }

    public List<TaskView<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) {
        return tasks.values().stream().peek(taskView -> {
            try {
                taskView.task.get(timeout, timeUnit);
            } catch (InterruptedException|ExecutionException|TimeoutException|CancellationException e) {
                logger.error("task interrupted while running", e);
            }
        }).collect(toList());
    }

    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(taskView -> taskView.getState() != TaskView.State.RUNNING).map(t -> tasks.remove(t.id)).collect(toList());
    }

    @Override
    public <V> TaskView<V> clearTask(String taskName) {
        return (TaskView<V>) tasks.remove(taskName);
    }

    public boolean stopTask(String taskName) {
        logger.info("cancelling task {}", taskName);
        return tasks.get(taskName).task.cancel(true);
    }

    @Override
    public Map<String, Boolean> stopAllTasks(User user) {
        return getTasks().stream().
                filter(t -> user.equals(t.getUser())).
                filter(t -> t.getState() == TaskView.State.RUNNING).collect(
                        toMap(t -> t.id, t -> stopTask(t.id)));
    }

    @Override
    public <V> TaskView<V> get(int timeOut, TimeUnit timeUnit) {
        throw new NotImplementedException("no need to wait for tasks with memory task manager");
    }
}
