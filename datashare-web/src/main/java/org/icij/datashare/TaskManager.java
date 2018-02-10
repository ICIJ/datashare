package org.icij.datashare;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public class TaskManager {
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final ConcurrentMap<Integer, Future> tasks = new ConcurrentHashMap<>();

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
