package org.icij.datashare.function;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Created by julien on 9/1/16.
 */
public interface ThrowingSupplier<T> extends Supplier<T> {

    @Override
    default T get() {
        try{
            return getThrows();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    T getThrows() throws Exception;


    default <R> ThrowingSupplier<R> andThen(ThrowingFunction<? super T, ? extends R> after) {
        Objects.requireNonNull(after);
        try {
            return () -> after.apply( get() );

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    default <R> ThrowingSupplier<R> andThen(Function<? super T, ? extends R> after) {
        Objects.requireNonNull(after);
        try {
            return () -> after.apply( get() );

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

}
