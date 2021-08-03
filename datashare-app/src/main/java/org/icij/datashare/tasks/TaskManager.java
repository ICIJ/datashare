package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

import static java.lang.Integer.parseInt;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.stream.Collectors.toList;

public class TaskManager {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ExecutorService executor;
    private final ConcurrentMap<String, MonitorableFutureTask<?>> tasks = new ConcurrentHashMap<>();

    @Inject
    public TaskManager(final PropertiesProvider provider) {
        Optional<String> parallelism = provider.get("parallelism");
        executor = parallelism.map(s -> newFixedThreadPool(parseInt(s))).
                   orElseGet( () -> newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    public MonitorableFutureTask<Void> startTask(final Runnable task) {
        MonitorableFutureTask<Void> futureTask = new MonitorableFutureTask<>(task, null);
        executor.submit(futureTask);
        tasks.put(futureTask.toString(), futureTask);
        return futureTask;
    }

    public <V> MonitorableFutureTask<V> startTask(final Callable<V> task, final Runnable callback) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<V>(task) {
            @Override protected void done() { callback.run();}
        };
        executor.submit(futureTask);
        tasks.put(futureTask.toString(), futureTask);
        return futureTask;
    }

    public <V> MonitorableFutureTask<V> startTask(final Callable<V> task, Map<String, Object> properties) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<V>(task, properties);
        executor.submit(futureTask);
        tasks.put(futureTask.toString(), futureTask);
        return futureTask;
    }

    public <V> MonitorableFutureTask<V> startTask(final Callable<V> task) {
        MonitorableFutureTask<V> futureTask = new MonitorableFutureTask<>(task);
        executor.submit(futureTask);
        tasks.put(futureTask.toString(), futureTask);
        return futureTask;
    }

    public MonitorableFutureTask<?> getTask(final String taskName) {
        return tasks.get(taskName);
    }

    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public boolean shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        executor.shutdown();
        return executor.awaitTermination(timeout, timeUnit);
    }

    public Collection<MonitorableFutureTask<?>> getTasks() {
        return tasks.values();
    }

    public List<MonitorableFutureTask<?>> waitTasksToBeDone(int timeout, TimeUnit timeUnit) {
        return getTasks().stream().peek(monitorableFutureTask -> {
            try {
                monitorableFutureTask.get(timeout, timeUnit);
            } catch (InterruptedException|ExecutionException|TimeoutException|CancellationException e) {
                logger.error("task interrupted while running", e);
            }
        }).collect(toList());
    }

    public List<MonitorableFutureTask<?>> cleanDoneTasks() {
        return getTasks().stream().filter(FutureTask::isDone).map(t -> tasks.remove(t.toString())).collect(toList());
    }

    public boolean stopTask(String taskName) {
        logger.info("cancelling task {}", taskName);
        return getTask(taskName).cancel(true);
    }
}
