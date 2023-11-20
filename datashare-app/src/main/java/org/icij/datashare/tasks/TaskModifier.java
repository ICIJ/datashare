package org.icij.datashare.tasks;

@FunctionalInterface
public interface TaskModifier {
    Void progress(String taskId, double rate);
}
