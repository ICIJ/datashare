package org.icij.datashare.asynctasks;

@FunctionalInterface
public interface TaskModifier {
    Void progress(String taskId, double rate);
}
