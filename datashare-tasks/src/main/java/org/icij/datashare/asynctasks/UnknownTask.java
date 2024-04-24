package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    private final ReflectiveOperationException exc;

    public UnknownTask(String s, ReflectiveOperationException source) {
        super(s);
        this.exc = source;
    }
}
