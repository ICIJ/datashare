package org.icij.datashare.asynctasks;

public record Progress(double progress, double maxProgress) {
    double asDouble() {
        if (maxProgress == 0f) {
            return 0f;
        }
        return progress / maxProgress;
    }
}