package org.icij.datashare.tasks;

import static java.lang.String.format;

public class CancelException extends RuntimeException {
    final String taskId;
    final boolean requeue;

    public CancelException(String taskId) {
        this(taskId, false);
    }

    public CancelException(String taskId, boolean requeue) {
        super(format("cancel %s", taskId));
        this.taskId = taskId;
        this.requeue = requeue;
    }
}