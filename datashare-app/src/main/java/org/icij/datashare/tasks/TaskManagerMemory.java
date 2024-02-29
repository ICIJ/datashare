package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Integer.parseInt;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@Singleton
public class TaskManagerMemory implements TaskManager, TaskSupplier {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final ConcurrentMap<String, TaskView<?>> tasks = new ConcurrentHashMap<>();
    private final BlockingQueue<TaskView<?>> taskQueue;

    @Inject
    public TaskManagerMemory(final PropertiesProvider provider, BlockingQueue<TaskView<?>> taskQueue) {
        this.taskQueue = taskQueue;
        Optional<String> parallelism = provider.get("parallelism");
        executor = parallelism.map(s -> newFixedThreadPool(parseInt(s))).
                   orElseGet( () -> newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    @Override
    public <V> TaskView<V> startTask(String taskName, User user, Map<String, Object> properties) {
        return startTask(new TaskView<>(taskName, user, properties));
    }

    @Override
    public <V> TaskView<V> startTask(String id, String taskName, User user) throws IOException {
        return startTask(new TaskView<>(id, taskName, user, new HashMap<>()));
    }

    private <V> TaskView<V> startTask(TaskView<V> taskView) {
        save(taskView);
        taskQueue.offer(taskView);
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
        TaskView<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            taskView.setProgress(rate);
        } else {
            logger.warn("unknown task id <{}> for progress={} call", taskId, rate);
        }
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        TaskView<V> taskView = (TaskView<V>) tasks.get(taskId);
        if (taskView != null) {
            taskView.setResult(result);
        } else {
            logger.warn("unknown task id <{}> for result={} call", taskId, result);
        }
    }

    @Override
    public <V extends Serializable> void cancel(TaskView<V> task) {
        taskQueue.offer(task);
    }

    @Override
    public void error(String taskId, Throwable reason) {

    }

    private void save(TaskView<?> taskView) {
        tasks.put(taskView.id, taskView);
    }

    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        taskQueue.offer(TaskView.nullObject());
        executor.shutdown();
        return executor.awaitTermination(timeout, timeUnit);
    }

    public List<TaskView<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) {
        return tasks.values().stream().peek(taskView -> {
            try {
                if (taskView.task != null) {
                    taskView.task.get(timeout, timeUnit);
                }
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
    public <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (TaskView<V>) taskQueue.poll(timeOut, timeUnit);
    }

    @Override
    public void close() throws IOException {
        shutdownNow();
    }
}
