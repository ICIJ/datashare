package org.icij.datashare.function;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Created by julien on 7/12/16.
 */

@FunctionalInterface
public interface ThrowingBiConsumer<T,U> extends BiConsumer<T,U> {

    @Override
    default void accept(T t, U u) {
        try{
            acceptThrows(t, u);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void acceptThrows(T t, U u) throws Exception;


    default ThrowingBiConsumer<T,U> andThen(ThrowingBiConsumer<? super T, ? super U> after) {
        Objects.requireNonNull(after);
        try {
            return (T t, U u) -> { accept(t, u); after.accept(t, u); };

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    default ThrowingBiConsumer<T,U> andThen(BiConsumer<? super T, ? super U> after) {
        Objects.requireNonNull(after);
        try {
            return (T t, U u) -> { accept(t, u); after.accept(t, u); };

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

}
