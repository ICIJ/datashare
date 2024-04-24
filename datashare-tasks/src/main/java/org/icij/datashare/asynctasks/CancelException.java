package org.icij.datashare.asynctasks;

public class CancelException extends RuntimeException {
    final boolean requeue;

    public CancelException(boolean requeue) {
        this.requeue = requeue;
    }
}