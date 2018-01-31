package org.icij.datashare.function;

import org.icij.datashare.text.Language;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;


/**
 * Utility functions
 *
 * Created by julien on 7/12/16.
 */
public class Functions {

    /**
     * Zip Streams {@code a} and {@code b} with {@code zipper}
     *
     * http://stackoverflow.com/questions/17640754/zipping-streams-using-jdk8-with-lambda-java-util-stream-streams-zip
     *
     * @param a      the first stream
     * @param b      the second stream
     * @param zipper the streams combining
     * @param <A>    the type of first stream
     * @param <B>    the type of second stream
     * @param <C>    the combined type
     * @return a Stream of type C
     */
    public static<A, B, C> Stream<C> zip(Stream<? extends A> a,
                                         Stream<? extends B> b,
                                         BiFunction<? super A, ? super B, ? extends C> zipper) {
        Objects.requireNonNull(zipper);

        @SuppressWarnings("unchecked")
        Spliterator<A> spliterA = (Spliterator<A>) Objects.requireNonNull(a).spliterator();
        @SuppressWarnings("unchecked")
        Spliterator<B> spliterB = (Spliterator<B>) Objects.requireNonNull(b).spliterator();

        // Zipping looses DISTINCT and SORTED characteristics
        int charcs = (
                spliterA.characteristics() &
                spliterB.characteristics() &
                ~ (Spliterator.DISTINCT | Spliterator.SORTED)
        );

        long zipSize = ( (charcs & Spliterator.SIZED) != 0) ?
                  Math.min(spliterA.getExactSizeIfKnown(), spliterB.getExactSizeIfKnown())
                : -1;

        Iterator<A> aIter = Spliterators.iterator(spliterA);
        Iterator<B> bIter = Spliterators.iterator(spliterB);
        Iterator<C> cIter = new Iterator<C>() {
            @Override
            public boolean hasNext() {
                return aIter.hasNext() && bIter.hasNext();
            }
            @Override
            public C next() {
                return zipper.apply(aIter.next(), bIter.next());
            }
        };

        Spliterator<C> split = Spliterators.spliterator(cIter, zipSize, charcs);
        return (a.isParallel() || b.isParallel()) ?
                  StreamSupport.stream(split, true)
                : StreamSupport.stream(split, false);
    }

    public static final Function<String, String> capitalize =
            str -> str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();

    public static final Function<String, Language> parseLanguage = Language::parse;
}
