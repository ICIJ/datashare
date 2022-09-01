package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

import static java.lang.Integer.parseInt;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.toList;

public class TaskManagerMemory implements TaskManager {
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
    public TaskView<Void> startTask(final Runnable task) {
        MonitorableFutureTask<Void> futureTask = new MonitorableFutureTask<>(task, null);
        TaskView<Void> taskView = new TaskView<>(futureTask);
        executor.submit(futureTask);
        save(taskView);
        return taskView;
    }

    @Override
    public <V> TaskView<V> startTask(final Callable<V> task, final Runnable callback) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<V>(task) {
            @Override protected void done() { callback.run();}
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

    public TaskView<?> get(final String taskName) {
        return tasks.get(taskName);
    }

    @Override
    public List<TaskView<?>> get() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public <V> Void save(TaskView<V> task) {
        tasks.put(task.name, task);
        return null;
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
        return tasks.values().stream().filter(taskView -> taskView.getState() != TaskView.State.RUNNING).map(t -> tasks.remove(t.name)).collect(toList());
    }

    @Override
    public TaskView<?> clearTask(String taskName) {
        return tasks.remove(taskName);
    }

    public boolean stopTask(String taskName) {
        logger.info("cancelling task {}", taskName);
        return tasks.get(taskName).task.cancel(true);
    }
}
