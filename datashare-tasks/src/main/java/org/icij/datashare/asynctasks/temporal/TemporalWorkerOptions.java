package org.icij.datashare.asynctasks.temporal;

public record TemporalWorkerOptions(int maxConcurrentActivitySize) {
    public TemporalWorkerOptions {
        if (maxConcurrentActivitySize < 1) {
            throw new IllegalArgumentException("worker concurrency must be at least 1, got %d"
                    .formatted(maxConcurrentActivitySize));
        }
    }
}
