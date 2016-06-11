package org.icij.datashare.text.util.function;

import java.util.function.BiFunction;

/**
 * Created by julien on 4/18/16.
 */
public interface BiThrowingFunction<T, U, R> extends BiFunction<T, U, R> {

    @Override
    default R apply(T t, U u) {
        try{
            return applyThrows(t, u);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    R applyThrows(T t, U u) throws Exception;
}
