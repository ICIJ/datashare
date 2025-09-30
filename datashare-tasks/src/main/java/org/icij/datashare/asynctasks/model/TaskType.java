package org.icij.datashare.asynctasks.model;

import java.util.HashSet;
import java.util.Set;

public enum TaskType {
    NOOP;

    private static final Set<TaskType> BUILT_IN_TASKS = new HashSet<>();

    public static boolean isBuiltIn(TaskType taskType) {
        return BUILT_IN_TASKS.contains(taskType);
    }
}
