package org.icij.datashare.function;

/**
 * Pair Class
 *
 * http://stackoverflow.com/questions/521171/a-java-collection-of-value-pairs-tuples
 *
 * Created by julien on 7/12/16.
 */
public class Pair<T1, T2> {

    private final T1 first;
    private final T2 second;

    public Pair(T1 fst, T2 snd) {
        first  = fst;
        second = snd;
    }

    public T1 _1() { return first; }
    public T2 _2() { return second; }

    @Override
    public int hashCode() {
        return first.hashCode() ^ second.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if ( ! (o instanceof Pair) ) {
            return false;
        }
        Pair objPair = (Pair) o;
        return  first .equals(objPair._1()) &&
                second.equals(objPair._2());
    }

    @Override
    public String toString() {
        return String.format("(%s,%s)", first, second);
    }
}