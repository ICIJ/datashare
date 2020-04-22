package org.icij.datashare.function;

import java.util.function.BiFunction;

/**
 * Created by julien on 4/18/16.
 */
public interface ThrowingBiFunction<T, U, R> extends BiFunction<T, U, R> {

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
