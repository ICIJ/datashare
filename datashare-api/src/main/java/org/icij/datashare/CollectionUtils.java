package org.icij.datashare;

import java.util.SortedSet;
import java.util.TreeSet;

import static java.util.Arrays.asList;

public class CollectionUtils {
    public static <T> SortedSet<T> asSet(T... elements) {
        return new TreeSet<>(asList(elements));
    }
}
