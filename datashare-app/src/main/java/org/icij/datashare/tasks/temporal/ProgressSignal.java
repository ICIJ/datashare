package org.icij.datashare.tasks.temporal;

public record ProgressSignal(String runId, String activityId, double progress, double weight) {
    public ProgressSignal(String runId, String activityId) {
        this(runId, activityId, 0.0f, 1.0f);
    }
}
