package org.icij.datashare;

import com.google.inject.Inject;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

import static java.lang.Integer.valueOf;
import static java.util.concurrent.Executors.newFixedThreadPool;

public class TaskManager {
    private final ExecutorService executor;
    private final ConcurrentMap<Integer, FutureTask> tasks = new ConcurrentHashMap<>();

    @Inject
    public TaskManager(final PropertiesProvider provider) {
        Optional<String> parallelism = provider.get("parallelism");
        executor = parallelism.map(s -> newFixedThreadPool(valueOf(s))).
                   orElseGet( () -> newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    public FutureTask<Void> startTask(final Runnable task) {
        FutureTask<Void> futureTask = new FutureTask<>(task, null);
        executor.submit(futureTask);
        tasks.put(futureTask.hashCode(), futureTask);
        return futureTask;
    }

    public FutureTask<Void> startTask(final Runnable task, final Runnable callback) {
        FutureTask<Void> futureTask = new FutureTask<Void>(task, null) {
            @Override protected void done() {
                callback.run();
            }
        };
        executor.submit(futureTask);
        tasks.put(futureTask.hashCode(), futureTask);
        return futureTask;
    }
    public <V> FutureTask<V> startTask(final Callable<V> task, final Runnable callback) {
        FutureTask<V> futureTask = new FutureTask<V>(task) {
            @Override protected void done() {
                callback.run();
            }
        };
        executor.submit(futureTask);
        tasks.put(futureTask.hashCode(), futureTask);
        return futureTask;
    }

    public <V> FutureTask<V> startTask(final Callable<V> task) {
        FutureTask<V> futureTask = new FutureTask<>(task);
        executor.submit(futureTask);
        tasks.put(futureTask.hashCode(), futureTask);
        return futureTask;
    }

    public FutureTask getTask(final int tId) {
        return tasks.get(tId);
    }

    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public void shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(timeout, timeUnit);
    }

    public Collection<FutureTask> getTasks() {
        return tasks.values();
    }
}
