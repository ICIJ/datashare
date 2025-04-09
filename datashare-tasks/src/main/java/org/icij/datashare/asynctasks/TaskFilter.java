package org.icij.datashare.asynctasks;

@FunctionalInterface
public interface TaskFilter {
    boolean filter(Task s);
}
