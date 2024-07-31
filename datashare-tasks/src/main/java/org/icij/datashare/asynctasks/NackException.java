package org.icij.datashare.asynctasks;

public class NackException extends RuntimeException {
    public final boolean requeue;

    public NackException(Throwable cause, boolean requeue) {
        super(cause);
        this.requeue = requeue;
    }
}
