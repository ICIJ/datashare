package org.icij.datashare.asynctasks;

public class UnknownTask extends RuntimeException {
    public UnknownTask(String s, ReflectiveOperationException source) {
        super(s, source);
    }
}
