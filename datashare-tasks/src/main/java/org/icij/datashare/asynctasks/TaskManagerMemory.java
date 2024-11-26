package org.icij.datashare.asynctasks;

import java.util.Optional;
import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;


public class TaskManagerMemory implements TaskManager, TaskSupplier {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final ConcurrentMap<String, TaskMetadata<?>> taskMetas = new ConcurrentHashMap<>();
    private final BlockingQueue<Task<?>> taskQueue;
    private final List<TaskWorkerLoop> loops;
    private final AtomicInteger executedTasks = new AtomicInteger(0);

    public TaskManagerMemory(BlockingQueue<Task<?>> taskQueue, TaskFactory taskFactory) {
        this(taskQueue, taskFactory, new PropertiesProvider(), new CountDownLatch(1));
    }

    public TaskManagerMemory(BlockingQueue<Task<?>> taskQueue, TaskFactory taskFactory, PropertiesProvider propertiesProvider, CountDownLatch latch) {
        this.taskQueue = taskQueue;
        int parallelism = parseInt(propertiesProvider.get("parallelism").orElse("1"));
        logger.info("running TaskManager with {} threads", parallelism);
        executor = Executors.newFixedThreadPool(parallelism);
        loops = IntStream.range(0, parallelism).mapToObj(i -> new TaskWorkerLoop(taskFactory, this, latch)).collect(Collectors.toList());
        loops.forEach(executor::submit);
    }

    public <V> Task<V> getTask(final String taskId) {
        return (Task<V>) Optional.ofNullable(taskMetas.get(taskId)).map(TaskMetadata::task).orElse(null);
    }

    @Override
    public List<Task<?>> getTasks() {
        return taskMetas.values().stream().map(TaskMetadata::task).collect(toList());
    }

    @Override
    public List<Task<?>> getTasks(Pattern pattern) throws IOException {
        return this.getTasks(taskMetas.values().stream().map(TaskMetadata::task), pattern);
    }

    @Override
    public Group getTaskGroup(String taskId) {
        return taskMetas.get(taskId).group();
    }

    @Override
    public Void progress(String taskId, double rate) {
        Task<?> taskView = getTask(taskId);
        if (taskView != null) {
            taskView.setProgress(rate);
        } else {
            logger.warn("unknown task id <{}> for progress={} call", taskId, rate);
        }
        return null;
    }

    @Override
    public <V extends Serializable> void result(String taskId, V result) {
        Task<V> taskView = getTask(taskId);
        if (taskView != null) {
            taskView.setResult(result);
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for result={} call", taskId, result);
        }
    }

    @Override
    public void canceled(Task<?> task, boolean requeue) {
        Task<?> taskView = taskMetas.get(task.id).task();
        if (taskView != null) {
            taskView.cancel();
            if (requeue) {
                taskQueue.offer(task);
            }
        }
    }

    @Override
    public void error(String taskId, TaskError reason) {
        Task<?> taskView = taskMetas.get(taskId).task();
        if (taskView != null) {
            taskView.setError(reason);
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for error={} call", taskId, reason.toString());
        }
    }

    @Override
    public <V> void saveMetadata(TaskMetadata<V> taskMetadata) throws TaskAlreadyExists {
        String taskId = taskMetadata.taskId();
        if (taskMetas.containsKey(taskId)) {
            throw new TaskAlreadyExists(taskId);
        }
        this.taskMetas.put(taskId, taskMetadata);
    }

    @Override
    public <V> void persistUpdate(Task<V> task) throws UnknownTask {
        TaskMetadata<V> updated = (TaskMetadata<V>) taskMetas.get(task.id);
        if (updated == null) {
            throw new UnknownTask(task.id);
        }
        updated = updated.withTask(task);
        this.taskMetas.put(task.id, updated);
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
        return taskMetas.values().stream().peek(m -> {
            try {
                m.task().getResult(timeout, timeUnit);
            } catch (InterruptedException | CancellationException e) {
                logger.error("task interrupted while running", e);
            }
        }).map(TaskMetadata::task).collect(toList());
    }

    public List<Task<?>> clearDoneTasks() {
        return taskMetas.values().stream().map(TaskMetadata::task).filter(Task::isFinished)
            .map(t -> taskMetas.remove(t.id).task()).collect(toList());
    }

    @Override
    public <V> Task<V> clearTask(String taskId) {
        if (getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return (Task<V>) taskMetas.remove(taskId).task();
    }

    public boolean stopTask(String taskId) {
        Task<?> taskView = getTask(taskId);
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
        taskMetas.clear();
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        // no need for this we use task runner reference for stopping tasks
    }

    @Override
    public void waitForConsumer() {}
}
