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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;


public class TaskManagerMemory implements TaskManager, TaskSupplier {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final TaskRepository taskRepository;
    private final BlockingQueue<Task<?>> taskQueue;
    private final List<TaskWorkerLoop> loops;
    private final AtomicInteger executedTasks = new AtomicInteger(0);
    private final int pollingInterval;

    public TaskManagerMemory(TaskFactory taskFactory) {
        this(taskFactory, new PropertiesProvider(), new TaskRepositoryMemory(), new CountDownLatch(1));
    }

    public TaskManagerMemory(TaskFactory taskFactory, PropertiesProvider propertiesProvider, TaskRepository tasks, CountDownLatch latch) {
        this.taskQueue = new LinkedBlockingQueue<>();
        int parallelism = parseInt(propertiesProvider.get("parallelism").orElse("1"));
        pollingInterval = Integer.parseInt(propertiesProvider.get("pollingInterval").orElse("60"));
        logger.info("running TaskManager {} with {} workers", this, parallelism);
        executor = Executors.newFixedThreadPool(parallelism);
        loops = IntStream.range(0, parallelism).mapToObj(i -> new TaskWorkerLoop(taskFactory, this, latch, pollingInterval)).collect(Collectors.toList());
        loops.forEach(executor::submit);
        this.taskRepository = tasks;
    }

    public <V> Task<V> getTask(final String taskId) {
        return (Task<V>) Optional.ofNullable(taskRepository.get(taskId)).map(TaskMetadata::task).orElse(null);
    }

    @Override
    public List<Task<?>> getTasks() {
        return taskRepository.values().stream().map(TaskMetadata::task).collect(toList());
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
        Task<?> taskView = taskRepository.get(task.id).task();
        if (taskView != null) {
            taskView.cancel();
            if (requeue) {
                taskQueue.offer(task);
            }
        }
    }

    @Override
    public void error(String taskId, TaskError reason) {
        Task<?> taskView = taskRepository.get(taskId).task();
        if (taskView != null) {
            taskView.setError(reason);
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for error={} call", taskId, reason.toString());
        }
    }


    @Override
    public Group getTaskGroup(String taskId) {
        return taskRepository.get(taskId).group();
    }

    @Override
    public <V> void enqueue(Task<V> task) {
        taskQueue.add(task);
    }

    @Override
    public boolean shutdown() throws IOException {
        executor.shutdown();
        loops.forEach(TaskWorkerLoop::exit);
        try {
            return executor.awaitTermination(pollingInterval * 2L, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Task<?>> clearDoneTasks() {
        return taskRepository.values().stream().map(TaskMetadata::task).filter(Task::isFinished)
            .map(t -> taskRepository.remove(t.id).task()).collect(toList());
    }

    @Override
    public <V> Task<V> clearTask(String taskId) {
        if (getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        return (Task<V>) taskRepository.remove(taskId).task();
    }

    public boolean stopTask(String taskId) {
        Task<?> taskView = getTask(taskId);
        if (taskView != null) {
            switch (taskView.getState()) {
                case CREATED:
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

    @Override
    public List<Task<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) throws IOException {
        return getTasks().stream().peek(taskView -> {
            try {
                taskView.getResult(timeout, timeUnit);
            } catch (InterruptedException e) {
                logger.error("getResult interrupted while waiting for result", e);
            }
        }).toList();
    }

    int numberOfExecutedTasks() {
        return executedTasks.get();
    }

    @Override
    public void clear() {
        executedTasks.set(0);
        taskQueue.clear();
        taskRepository.clear();
    }

    @Override
    public boolean getHealth() {
        return !executor.isShutdown();
    }

    @Override
    public <V> void persist(Task<?> task, Group group) throws TaskAlreadyExists {
        this.taskRepository.persist(task, group);
    }

    @Override
    public <V> void update(Task<V> task) throws IOException {
        this.taskRepository.update(task);
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        // no need for this we use task runner reference for stopping tasks
    }

    @Override
    public void waitForConsumer() {}
}
