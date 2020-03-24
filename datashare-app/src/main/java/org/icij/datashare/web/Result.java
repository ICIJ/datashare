package org.icij.datashare.web;

public class Result<T> {
    private final T result;
    public Result(T result) {
        this.result = result;
    }
}
