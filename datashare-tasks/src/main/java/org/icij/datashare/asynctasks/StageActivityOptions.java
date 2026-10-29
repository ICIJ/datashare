package org.icij.datashare.asynctasks;

import java.time.Duration;

/**
 * Contains how each stage is configured to run in Temporal as an activity
 * @param startToCloseTimeout maximum duration of one attempt
 * @param maxAttempts attempts before the activity fails, 0 for unbounded
 */
public record StageActivityOptions(Duration startToCloseTimeout, int maxAttempts) {
    public static final StageActivityOptions DEFAULT = new StageActivityOptions(Duration.ofMinutes(1), 0);

    public StageActivityOptions {
        if (startToCloseTimeout == null || startToCloseTimeout.isZero() || startToCloseTimeout.isNegative()) {
            throw new IllegalArgumentException(
                    "start to close timeout must be positive, got %s".formatted(startToCloseTimeout));
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("max attempts must be at least 0, got %d".formatted(maxAttempts));
        }
    }
}
