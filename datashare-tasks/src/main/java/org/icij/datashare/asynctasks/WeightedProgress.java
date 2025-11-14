package org.icij.datashare.asynctasks;

public record WeightedProgress(Progress progress, int weight) {
    public WeightedProgress(double progress, double maxProgress, int weight) {
        this(new Progress(progress, maxProgress), weight);
    }
}