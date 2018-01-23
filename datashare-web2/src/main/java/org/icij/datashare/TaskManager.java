package org.icij.datashare;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

public class TaskManager {
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private final ConcurrentMap<Integer, Future> tasks = new ConcurrentHashMap<>();

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

    public Collection<Future> getTasks() {
        return tasks.values();
    }
}
