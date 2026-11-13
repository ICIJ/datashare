package org.icij.datashare.asynctasks.temporal;

import java.time.Duration;

/**
 * @param maxConcurrentActivitySize concurrency limit of each worker
 * @param shutdownTimeout how long closing the workers waits for the running activities before interrupting them
 */
public record TemporalWorkerOptions(int maxConcurrentActivitySize, Duration shutdownTimeout) {
    private static final int DEFAULT_TEMPORAL_MAX_CONCURRENT_ACTIVITY_SIZE = 200;
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(300);

    public TemporalWorkerOptions {
        if (maxConcurrentActivitySize < 1) {
            throw new IllegalArgumentException(
                    "worker concurrency must be at least 1, got %d".formatted(maxConcurrentActivitySize));
        }
        if (shutdownTimeout.isNegative()) {
            throw new IllegalArgumentException("shutdown timeout must not be negative, got %s".formatted(shutdownTimeout));
        }
    }

    public TemporalWorkerOptions(int maxConcurrentActivitySize) {
        this(maxConcurrentActivitySize, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    public static TemporalWorkerOptions defaultValues() {
        return new TemporalWorkerOptions(DEFAULT_TEMPORAL_MAX_CONCURRENT_ACTIVITY_SIZE);
    }

    public TemporalWorkerOptions withShutdownTimeout(Duration shutdownTimeout) {
        return new TemporalWorkerOptions(maxConcurrentActivitySize, shutdownTimeout);
    }
}
