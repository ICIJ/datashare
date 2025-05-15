package org.icij.datashare.asynctasks;

import java.util.stream.Stream;
import org.apache.commons.lang3.NotImplementedException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.bus.amqp.Event;
import org.icij.datashare.asynctasks.bus.amqp.TaskError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.lang.Integer.parseInt;
import static org.icij.datashare.asynctasks.Task.State.FINAL_STATES;


public class TaskManagerMemory implements TaskManager, TaskSupplier {
    protected static final int DEFAULT_TASK_POLLING_INTERVAL_MS = 5000;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final TaskRepository tasks;
    private final BlockingQueue<Task> taskQueue;
    private final List<TaskWorkerLoop> loops;
    private final AtomicInteger executedTasks = new AtomicInteger(0);
    private final int pollingInterval;
    private final int taskPollingIntervalMs;

    public TaskManagerMemory(TaskFactory taskFactory) {
        this(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider(), new CountDownLatch(1));
    }

    public TaskManagerMemory(TaskFactory taskFactory, TaskRepository tasks, PropertiesProvider propertiesProvider, CountDownLatch latch) {
        this.taskQueue = new LinkedBlockingQueue<>();
        int parallelism = parseInt(propertiesProvider.get("taskWorkers").orElse("1"));
        pollingInterval = Integer.parseInt(propertiesProvider.get("pollingInterval").orElse("60"));
        taskPollingIntervalMs = Integer.parseInt(propertiesProvider.get("taskManagerPollingIntervalMilliseconds").orElse(String.valueOf(DEFAULT_TASK_POLLING_INTERVAL_MS)));
        logger.info("running TaskManager {} with {} workers", this, parallelism);
        executor = Executors.newFixedThreadPool(parallelism);
        loops = IntStream.range(0, parallelism).mapToObj(i -> new TaskWorkerLoop(taskFactory, this, latch, pollingInterval)).collect(Collectors.toList());
        loops.forEach(executor::submit);
        this.tasks = tasks;
    }

    public Task getTask(final String taskId) throws UnknownTask, IOException {
        return tasks.getTask(taskId);
    }

    @Override
    public Stream<Task> getTasks(TaskFilters filters) throws IOException {
        return tasks.getTasks(filters);
    }

    @Override
    public Void progress(String taskId, double rate) {
        try {
            Task task = getTask(taskId);
            task.setProgress(rate);
            update(task);
        } catch (UnknownTask ex) {
            logger.warn("unknown task id <{}> for progress={} call", taskId, rate);
        } catch (IOException e) {
            logger.error("error while updating progress for task <{}>", taskId, e);
        }
        return null;
    }

    @Override
    public void result(String taskId, byte[] result) {
        try {
            Task task = getTask(taskId);
            task.setResult(result);
            update(task);
            executedTasks.incrementAndGet();
        } catch (UnknownTask ex) {
            logger.warn("unknown task id <{}> for result={} call", taskId, new String(result));
        } catch (IOException e) {
            logger.error("error while updating result for task <{}>", taskId, e);
        }
    }

    @Override
    public void canceled(Task task, boolean requeue) {
        Task taskView;
        try {
             taskView = getTask(task.id);
             taskView.cancel();
             update(taskView);
        } catch (UnknownTask ex) {
            logger.warn("unknown task id <{}> for cancel={} call", task.id, requeue);
        } catch (IOException e) {
            logger.error("error while canceling task <{}>", task.getId(), e);
        }
        if (requeue) {
            taskQueue.offer(task);
        }
    }

    @Override
    public void error(String taskId, TaskError reason) {
        try {
            Task task = getTask(taskId);
            task.setError(reason);
            update(task);
            executedTasks.incrementAndGet();
        } catch (UnknownTask ex) {
            logger.warn("unknown task id <{}> for error={} call", taskId, reason.toString());
        } catch (IOException e) {
            logger.error("error while updating error for task <{}>", taskId, e);
        }
    }

    @Override
    public Group getTaskGroup(String taskId) throws IOException {
        return tasks.getTaskGroup(taskId);
    }

    @Override
    public void enqueue(Task task) {
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

    @Override
    public List<Task> clearDoneTasks(TaskFilters filters) throws IOException {
        synchronized (tasks) {
            // Require tasks to be in final state and apply user filters
            Stream<Task> taskStream = tasks.getTasks(filters.withStates(FINAL_STATES))
                .map(t -> {
                    try {
                        return tasks.delete(t.id);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            return taskStream.toList();
        }
    }

    @Override
    public Task clearTask(String taskId) throws UnknownTask, IOException {
        if (getTask(taskId).getState() == Task.State.RUNNING) {
            throw new IllegalStateException(String.format("task id <%s> is already in RUNNING state", taskId));
        }
        logger.info("deleting task id <{}>", taskId);
        synchronized (tasks) {
            return tasks.delete(taskId);
        }
    }

    public boolean stopTask(String taskId) throws UnknownTask, IOException {
        Task taskView = tasks.getTask(taskId);
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
    public Task get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return taskQueue.poll(timeOut, timeUnit);
    }

    @Override
    public void consumeTasks(Consumer<Task> taskCallback) {
        throw new NotImplementedException("no loop is provided by memory databus. Use get(int, TimeUnit) method.");
    }

    @Override
    public int getTerminationPollingInterval() {
        return taskPollingIntervalMs;
    }

    @Override
    public void close() throws IOException {
        executor.shutdown();
    }

    int numberOfExecutedTasks() {
        return executedTasks.get();
    }

    @Override
    public void clear() throws IOException {
        executedTasks.set(0);
        taskQueue.clear();
        synchronized (tasks) {
            tasks.deleteAll();
        }
    }

    @Override
    public boolean getHealth() {
        return !executor.isShutdown();
    }

    @Override
    public void insert(Task task, Group group) throws TaskAlreadyExists, IOException {
        synchronized (tasks) {
            tasks.insert(task, group);
        }
    }

    @Override
    public void update(Task task) throws IOException, UnknownTask {
        synchronized (tasks) {
            tasks.update(task);
        }
    }

    @Override
    public void addEventListener(Consumer<Event> callback) {
        // no need for this we use task runner reference for stopping tasks
    }

    @Override
    public void waitForConsumer() {}
}
