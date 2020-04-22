package org.icij.datashare.function;

import java.util.Objects;
import java.util.function.Function;

/**
 * Created by julien on 6/16/16.
 */
@FunctionalInterface
public interface QuadFunction<A, B, C, D, R> {

    R apply(A a, B b, C c, D d);

    default <S> QuadFunction<A, B, C, D, S> andThen (Function<? super R, ? extends S> after) {
        Objects.requireNonNull(after);
        return (A a, B b, C c, D d) -> after.apply( apply(a, b, c, d) );
    }

    default <T> QuadFunction<T, B, C, D, R> compose (Function<? super T, ? extends A> before) {
        Objects.requireNonNull(before);
        return (T t, B b, C c, D d) -> apply( before.apply(t), b, c, d );
    }

    static <A, B, C, D, R> Function<A, Function<B, Function<C, Function<D, R>>>> curry (final QuadFunction<A, B, C, D, R> f) {
        return (A a) -> (B b) -> (C c) -> (D d) -> f.apply(a, b, c, d);
    }

    static <A, B, C, D, R> QuadFunction<A, B, C, D, R> uncurry (Function<A, Function<B, Function<C, Function<D, R>>>> f) {
        return (A a, B b, C c, D d) -> f.apply(a).apply(b).apply(c).apply(d);
    }

}
