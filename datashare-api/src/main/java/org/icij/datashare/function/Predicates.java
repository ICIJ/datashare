package org.icij.datashare.function;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;


/**
 * Utility predicates
 *
 * Created by julien on 7/22/16.
 */
public class Predicates {

    // String is not empty
    public static final Predicate<String> notEmptyStr = s -> ! s.isEmpty();
    // List is not empty
    public static final Predicate<List> notEmptyList = l -> ! l.isEmpty();

    // Equal To
    public static final Function<Integer, Predicate<Integer>> isEQ = a -> b -> a.equals(b);

    // Greater Than
    public static final Function<Integer, Predicate<Integer>> isGT = a -> b -> b > a;
    // Greater or Equal
    public static final Function<Integer, Predicate<Integer>> isGE = a -> b -> b >= a;

    // Less Than
    public static final Function<Integer, Predicate<Integer>> isLT = a -> b -> b < a;
    // Less or Equal
    public static final Function<Integer, Predicate<Integer>> isLE = a -> b -> b <= a;

}
