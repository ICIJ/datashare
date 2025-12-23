package org.icij.datashare.tasks.temporal;

public record Progress(float current, float maxProgress) {
    public Progress(float maxProgress) {
        this(0.0f, maxProgress);
    }
}
