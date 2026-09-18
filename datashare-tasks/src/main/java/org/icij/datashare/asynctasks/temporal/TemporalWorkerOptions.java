package org.icij.datashare.asynctasks.temporal;

public record TemporalWorkerOptions(int maxConcurrentActivitySize) {
    private static final int DEFAULT_TEMPORAL_MAX_CONCURRENT_ACTIVITY_SIZE = 200;

    public TemporalWorkerOptions {
        if (maxConcurrentActivitySize < 1) {
            throw new IllegalArgumentException(
                    "worker concurrency must be at least 1, got %d".formatted(maxConcurrentActivitySize));
        }
    }

    public static TemporalWorkerOptions defaultValues() {
        return new TemporalWorkerOptions(DEFAULT_TEMPORAL_MAX_CONCURRENT_ACTIVITY_SIZE);
    }
}
