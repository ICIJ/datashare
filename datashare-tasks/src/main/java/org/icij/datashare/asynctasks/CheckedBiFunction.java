package org.icij.datashare.asynctasks;

@FunctionalInterface
public interface CheckedBiFunction<T, U, R, E extends Exception> {
    R apply(T var1, U var2) throws E;
}
