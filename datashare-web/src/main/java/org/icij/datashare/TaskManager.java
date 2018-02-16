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
    private final ConcurrentMap<Integer, Future> tasks = new ConcurrentHashMap<>();

    @Inject
    public TaskManager(final PropertiesProvider provider) {
        Optional<String> parallelism1 = provider.get("parallelism");
        executor = parallelism1.map(s -> newFixedThreadPool(valueOf(s))).
                orElseGet(() -> newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    public int startTask(final Runnable task) {
        Future<?> fut = executor.submit(task);
        tasks.put(fut.hashCode(), fut);
        return fut.hashCode();
    }

    public <V> int startTask(final Callable<V> task) {
        Future<?> fut = executor.submit(task);
        tasks.put(fut.hashCode(), fut);
        return fut.hashCode();
    }

    public Future getTask(final int tId) {
        return tasks.get(tId);
    }

    public List<Runnable> shutdownNow() {
        return executor.shutdownNow();
    }

    public void shutdownAndAwaitTermination(int timeout, TimeUnit timeUnit) throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(timeout, timeUnit);
    }

    public Collection<Future> getTasks() {
        return tasks.values();
    }
}
