package org.icij.datashare.asynctasks;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

public abstract class ScopedStageContext<O> implements StageContext<O> {
    private final Map<Class<?>, Memo<?>> resources = new ConcurrentHashMap<>();
    // for the close order (when a resource needs another one)
    private final Deque<Object> built = new ConcurrentLinkedDeque<>();

    @Override
    @SuppressWarnings("unchecked")
    public <R> R resource(Class<R> type, Supplier<R> factory) {
        Memo<R> memo = (Memo<R>) resources.computeIfAbsent(type, t -> new Memo<R>());
        return type.cast(memo.get(factory, type));
    }

    @Override
    public void close() {
        List<Object> toClose = new ArrayList<>();
        for (Object resource = built.pollFirst(); resource != null; resource = built.pollFirst()) {
            toClose.add(resource);
        }
        resources.clear();
        RuntimeException failure = null;
        for (Object resource : toClose) {
            if (resource instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    if (failure == null) {
                        failure = new RuntimeException("cannot close " + resource.getClass().getSimpleName(), e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private class Memo<R> {
        private volatile R value;

        R get(Supplier<R> factory, Class<R> type) {
            R resource = value;
            if (resource == null) {
                synchronized (this) {
                    resource = value;
                    if (resource == null) {
                        resource = Objects.requireNonNull(factory.get(), "no " + type.getSimpleName() + " was built");
                        built.addFirst(resource);
                        value = resource;
                    }
                }
            }
            return resource;
        }
    }
}
