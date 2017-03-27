package org.icij.datashare.function;

import java.util.Objects;
import java.util.function.Function;

/**
 * Created by julien on 6/16/16.
 */
@FunctionalInterface
public interface TerFunction<A, B, C, R> {

    R apply(A a, B b, C c);

    default <S> TerFunction<A, B, C, S> andThen (Function<? super R, ? extends S> after) {
        Objects.requireNonNull(after);
        return (A a, B b, C c) -> after.apply( apply(a, b, c) );
    }

    default <T> TerFunction<T, B, C, R> compose (Function<? super T, ? extends A> before) {
        Objects.requireNonNull(before);
        return (T t, B b, C c) -> apply( before.apply(t), b, c );
    }

    static <A, B, C, R> Function<A, Function<B, Function<C, R>>> curry (final TerFunction<A, B, C, R> f) {
        return (A a) -> (B b) -> (C c) -> f.apply(a, b, c);
    }

    static <A, B, C, R> TerFunction<A, B, C, R> uncurry (Function<A, Function<B, Function<C, R>>> f) {
        return (A a, B b, C c) -> f.apply(a).apply(b).apply(c);
    }

}
