package org.icij.datashare.function;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by julien on 6/21/16.
 */
@FunctionalInterface
public interface ThrowingConsumer<T> extends Consumer<T> {

    @Override
    default void accept(T t) {
        try{
            acceptThrows(t);

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    void acceptThrows(T t) throws Exception;


    default <V> ThrowingConsumer<V> compose(ThrowingFunction<? super V, ? extends T> before) {
        Objects.requireNonNull(before);
        try {
            return (V v) -> accept( before.apply(v) );

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    default <V> ThrowingConsumer<V> compose(Function<? super V, ? extends T> before) {
        Objects.requireNonNull(before);
        try {
            return (V v) -> accept( before.apply(v) );

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    default ThrowingConsumer<T> andThen(ThrowingConsumer<? super T> after) {
        Objects.requireNonNull(after);
        try {
            return (T t) -> { accept(t); after.accept(t); };

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    default ThrowingConsumer<T> andThen(Consumer<? super T> after) {
        Objects.requireNonNull(after);
        try {
            return (T t) -> { accept(t); after.accept(t); };

        } catch (Exception e){
            throw new RuntimeException(e);
        }
    }

}
