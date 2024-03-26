package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.icij.datashare.com.bus.amqp.TaskEvent;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.tasks.TaskView.State.RUNNING;


@Singleton
public class TaskManagerMemory implements TaskManager, TaskSupplier {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor = newSingleThreadExecutor();
    private final ConcurrentMap<String, TaskView<?>> tasks = new ConcurrentHashMap<>();
    private final BlockingQueue<TaskView<?>> taskQueue;
    private final TaskRunnerLoop loop;
    private final AtomicInteger executedTasks = new AtomicInteger(0);

    @Inject
    public TaskManagerMemory(BlockingQueue<TaskView<?>> taskQueue, TaskFactory taskFactory) {
        this(taskQueue, taskFactory, new CountDownLatch(1));
    }

    TaskManagerMemory(BlockingQueue<TaskView<?>> taskQueue, TaskFactory taskFactory, CountDownLatch latch) {
        this.taskQueue = taskQueue;
        loop = new TaskRunnerLoop(taskFactory, this, latch);
        executor.submit(loop);
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
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for result={} call", taskId, result);
        }
    }

    @Override
    public void canceled(TaskView<?> task, boolean requeue) {
        TaskView<?> taskView = tasks.get(task.id);
        if (taskView != null) {
            taskView.cancel();
            if (requeue) {
                taskQueue.offer(task);
            }
        }
    }

    @Override
    public void error(String taskId, Throwable reason) {
        TaskView<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            taskView.setError(reason);
            executedTasks.incrementAndGet();
        } else {
            logger.warn("unknown task id <{}> for error={} call", taskId, reason.toString());
        }
    }

    public void save(TaskView<?> taskView) {
        tasks.put(taskView.id, taskView);
    }

    @Override
    public void enqueue(TaskView<?> task) {
        taskQueue.add(task);
    }

    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        taskQueue.add(TaskView.nullObject());
        waitTasksToBeDone(timeout, timeUnit);
        executor.shutdownNow();
        return executor.awaitTermination(timeout, timeUnit);
    }

    public List<TaskView<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) {
        return tasks.values().stream().peek(taskView -> {
            try {
                taskView.getResult(timeout, timeUnit);
            } catch (InterruptedException | CancellationException e) {
                logger.error("task interrupted while running", e);
            }
        }).collect(toList());
    }

    public List<TaskView<?>> clearDoneTasks() {
        return tasks.values().stream().filter(taskView -> taskView.getState() != RUNNING).map(t -> tasks.remove(t.id)).collect(toList());
    }

    @Override
    public <V> TaskView<V> clearTask(String taskName) {
        return (TaskView<V>) tasks.remove(taskName);
    }

    public boolean stopTask(String taskId) {
        TaskView<?> taskView = tasks.get(taskId);
        if (taskView != null) {
            switch (taskView.getState()) {
                case QUEUED:
                    boolean removed = taskQueue.remove(taskView);
                    canceled(taskView, false);
                    return removed;
                case RUNNING:
                    loop.cancel(taskId, false);
                    return true;
            }
        } else {
            logger.warn("unknown task id <{}> for cancel call", taskId);
        }
        return false;
    }

    @Override
    public <V extends Serializable> TaskView<V> get(int timeOut, TimeUnit timeUnit) throws InterruptedException {
        return (TaskView<V>) taskQueue.poll(timeOut, timeUnit);
    }

    @Override
    public void close() throws IOException {
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
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
    public void addEventListener(Consumer<TaskEvent> callback) {
        // no need for this we use task runner reference for stopping tasks
    }
}
