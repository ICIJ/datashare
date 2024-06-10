package org.icij.datashare;

import java.util.LinkedHashSet;

import static java.util.Arrays.asList;

public class CollectionUtils {
    @SafeVarargs
    public static <T> LinkedHashSet<T> asSet(T... elements) {
        return new LinkedHashSet<>(asList(elements));
    }
}
