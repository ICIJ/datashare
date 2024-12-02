package org.icij.datashare.asynctasks;

import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.asynctasks.Task.State.RUNNING;


public class TaskManagerMemory implements TaskManager, TaskSupplier {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final ConcurrentMap<String, Task<?>> tasks = new ConcurrentHashMap<>();
    private final BlockingQueue<Task<?>> taskQueue;
    private final List<TaskWorkerLoop> loops;
    private final AtomicInteger executedTasks = new AtomicInteger(0);

    public TaskManagerMemory(TaskFactory taskFactory) {
        this(taskFactory, new PropertiesProvider(), new CountDownLatch(1));
    }

    public TaskManagerMemory(TaskFactory taskFactory, PropertiesProvider propertiesProvider, CountDownLatch latch) {
        this.taskQueue = new LinkedBlockingQueue<>();
        int parallelism = parseInt(propertiesProvider.get("parallelism").orElse("1"));
        logger.info("running TaskManager with {} workers", parallelism);
        executor = Executors.newFixedThreadPool(parallelism);
        loops = IntStream.range(0, parallelism).mapToObj(i -> new TaskWorkerLoop(taskFactory, this, latch)).collect(Collectors.toList());
        loops.forEach(executor::submit);
    }

    public <V> Task<V> getTask(final String taskId) {
        return (Task<V>) tasks.get(taskId);
    }

    @Override
    public List<Task<?>> getTasks() {
        return new LinkedList<>(tasks.values());
    }

    @Override
    public List<Task<?>> getTasks(User user, Pattern pattern) {
        return TaskManager.getTasks(tasks.values().stream(), user, pattern);
    }

    @Override
    public Void progress(String taskId, double rate) {
        Task<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            taskView.setProgress(rate);
        } else {
            logger.warn("unknown task id <{}> for progress={} call", taskId, rate);
        }
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        Task<V> taskView = (Task<V>) tasks.get(taskId);
        if (taskView != null) {
            taskView.setResult(result);
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for result={} call", taskId, result);
        }
    }

    @Override
    public void canceled(Task<?> task, boolean requeue) {
        Task<?> taskView = tasks.get(task.id);
        if (taskView != null) {
            taskView.cancel();
            if (requeue) {
                taskQueue.offer(task);
            }
        }
    }

    @Override
    public void error(String taskId, TaskError reason) {
        Task<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            taskView.setError(reason);
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for error={} call", taskId, reason.toString());
        }
    }

    public boolean save(Task<?> taskView) {
        Task<?> oldTask = tasks.put(taskView.id, taskView);
        return oldTask == null;
    }

    @Override
    public void enqueue(Task<?> task) {
        taskQueue.add(task);
    }

    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        waitTasksToBeDone(timeout, timeUnit);
        executor.shutdownNow();
        return executor.awaitTermination(timeout, timeUnit);
    }

    public List<Task<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) {
        return tasks.values().stream().peek(taskView -> {
            try {
                taskView.getResult(timeout, timeUnit);
            } catch (InterruptedException | CancellationException e) {
                logger.error("task interrupted while running", e);
            }
        }).collect(toList());
    }

    public List<Task<?>> clearDoneTasks() {
        return tasks.values().stream().filter(taskView -> taskView.getState() != RUNNING).map(t -> tasks.remove(t.id)).collect(toList());
    }

    @Override
    public <V> Task<V> clearTask(String taskName) {
        if (tasks.get(taskName).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskName));
        }
        logger.info("deleting task id <{}>", taskName);
        return (Task<V>) tasks.remove(taskName);
    }

    public boolean stopTask(String taskId) {
        Task<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            switch (taskView.getState()) {
                case QUEUED:
                    boolean removed = taskQueue.remove(taskView);
                    canceled(taskView, false);
                    return removed;
                case RUNNING:
                    logger.info("sending cancel event for {}", taskId);
                    loops.forEach(l -> l.cancel(taskId, false));
                    return true;
            }
        } else {
            logger.warn("unknown task id <{}> for cancel call", taskId);
        }
        return false;
    }

    @Override
    public <V extends Serializable> Task<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (Task<V>) taskQueue.poll(timeOut, timeUnit);
    }

    @Override
    public void consumeTasks(Consumer<Task> taskCallback) {
        throw new NotImplementedException("no loop is provided by memory databus. Use get(int, TimeUnit) method.");
    }

    @Override
    public void close() throws IOException {
        executor.shutdown();
    }

    int numberOfExecutedTasks() {
        return executedTasks.get();
    }

    @Override
    public void clear() {
        executedTasks.set(0);
        taskQueue.clear();
        tasks.clear();
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        // no need for this we use task runner reference for stopping tasks
    }

    @Override
    public void waitForConsumer() {}
}
