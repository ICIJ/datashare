package org.icij.datashare.tasks;

public class UnknownTaskType extends RuntimeException {
    public UnknownTaskType(IllegalArgumentException e) {
        super(e.getMessage(), e);
    }
}
