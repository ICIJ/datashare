package org.icij.datashare.tasks.temporal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hands what a stage emits to the workflow in batches. Every signal weighs on the history of the workflow, which
 * Temporal caps at about 50,000 events: one signal per emitted document would make the history grow with the
 * corpus. Nothing leaves before {@link #flush()} unless the buffer reaches its limit.
 */
class EmitBuffer<O> {
    private final int limit;
    private final Consumer<List<O>> sink;
    private final List<O> buffered = new ArrayList<>();

    EmitBuffer(int limit, Consumer<List<O>> sink) {
        this.limit = limit;
        this.sink = sink;
    }

    synchronized void add(List<O> items) {
        buffered.addAll(items);
        if (buffered.size() >= limit) {
            flush();
        }
    }

    /** Sends what is left: the caller does it once the batch went through, so a batch that is retried emits once. */
    synchronized void flush() {
        if (buffered.isEmpty()) {
            return;
        }
        sink.accept(List.copyOf(buffered));
        buffered.clear();
    }
}
