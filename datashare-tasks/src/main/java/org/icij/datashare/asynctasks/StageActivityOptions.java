package org.icij.datashare.asynctasks;

import java.time.Duration;

/**
 * Contains how each stage is configured to run in Temporal as an activity
 * @param startToCloseTimeout maximum duration of one attempt
 * @param maxAttempts attempts before the activity fails, 0 for unbounded
 * @param heartbeatTimeout how long the server waits for a sign of life of the worker before it gives the attempt to
 *                         another one. The activity runtime beats by itself while a stage runs, so a stage does not
 *                         have to; the same beat tells the activity that its workflow was cancelled. Zero disables it.
 */
public record StageActivityOptions(Duration startToCloseTimeout, int maxAttempts, Duration heartbeatTimeout) {
    public static final Duration DEFAULT_HEARTBEAT_TIMEOUT = Duration.ofMinutes(1);
    public static final StageActivityOptions DEFAULT = new StageActivityOptions(Duration.ofDays(1), 0);

    public StageActivityOptions(Duration startToCloseTimeout, int maxAttempts) {
        this(startToCloseTimeout, maxAttempts, DEFAULT_HEARTBEAT_TIMEOUT);
    }

    public StageActivityOptions {
        if (startToCloseTimeout == null || startToCloseTimeout.isZero() || startToCloseTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "start to close timeout must be positive, got %s".formatted(startToCloseTimeout));
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("max attempts must be at least 0, got %d".formatted(maxAttempts));
        }
        if (heartbeatTimeout == null || heartbeatTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "heartbeat timeout must be zero or positive, got %s".formatted(heartbeatTimeout));
        }
    }
}
