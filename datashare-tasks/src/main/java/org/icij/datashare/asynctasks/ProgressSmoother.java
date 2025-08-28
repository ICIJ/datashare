package org.icij.datashare.asynctasks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import org.icij.datashare.time.DatashareTime;


public class ProgressSmoother implements BiFunction<String, Double, Void> {

    private final BiConsumer<String, Double> progressFn;
    private final ConcurrentHashMap<String, Long> lastPublished = new ConcurrentHashMap<>();
    private final double minIntervalMS;

    public ProgressSmoother(final BiConsumer<String, Double> progressFn, double minIntervalS) {
        this.progressFn = progressFn;
        this.minIntervalMS = minIntervalS * 1000;
    }

    @Override
    public Void apply(String taskId, Double progress) {
        long now = DatashareTime.getInstance().currentTimeMillis();
        lastPublished.compute(
            taskId,
            (tId, lastUpdated) -> this.progressWithRate(tId, lastUpdated, now, progress)
        );
        if (progress == 1.0) {
            // Clear the cache when we hit 1.0
            lastPublished.remove(taskId);
        }
        return null;
    }

    private Long progressWithRate(final String taskId, final Long lastUpdated, final long now, final double newProgress) {
        boolean isNew = lastUpdated == null;
        boolean isComplete = newProgress == 1.0;
        if (isNew || isComplete || Math.abs(now - lastUpdated) > minIntervalMS) {
            progressFn.accept(taskId, newProgress);
            return now;
        }
        return lastUpdated;
    }
}
