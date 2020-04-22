package org.icij.datashare.function;

import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;


/**
 * Exception-throwing functions
 *
 * Created by julien on 4/21/16.
 */
public class ThrowingFunctions {

    // Join strings
    public static final ThrowingFunction<String, ThrowingFunction<List<?>, String>> join = sep -> list ->
            String.join(sep, list.stream().map(Object::toString).collect(Collectors.toList()));
    public static final ThrowingFunction<List<?>, String> joinComma  = join.apply(",");
    public static final ThrowingFunction<List<?>, String> joinSemcol = join.apply(";");
    public static final ThrowingFunction<List<?>, String> joinColon  = join.apply(":");
    public static final ThrowingFunction<List<?>, String> joinPipe   = join.apply("|");

    // Split string
    public static final ThrowingFunction<String, ThrowingFunction<String, List<String>>> split = val -> str ->
            Arrays.asList(str.split(val));
    public static final ThrowingFunction<String, List<String>> splitComma  = split.apply(",");
    public static final ThrowingFunction<String, List<String>> splitSemcol = split.apply(";");
    public static final ThrowingFunction<String, List<String>> splitColon  = split.apply(":");
    public static final ThrowingFunction<String, List<String>> splitPipe   = split.apply("|");

    // Remove from string
    public static final ThrowingFunction<String, ThrowingFunction<String, String>> removePattFrom = pttrn -> str ->
            str.replaceAll(pttrn, "");
    public static final ThrowingFunction<String, String> removeSpaces   = removePattFrom.apply("(\\s+)");
    public static final ThrowingFunction<String, String> removeNewLines = removePattFrom.apply("((\\r?\\n)+)");

    // Trim string
    public static final ThrowingFunction<String, String> trim = String::trim;

    // Parse string
    public static final ThrowingFunction<String, Charset> parseCharset  = Charset::forName;
    public static final ThrowingFunction<String, Boolean> parseBoolean  = Boolean::parseBoolean;
    public static final ThrowingFunction<String, Integer> parseInt      = Integer::parseInt;
    public static final ThrowingFunction<List<String>, List<Integer>> parseInts = ints ->
            ints.stream().map( parseInt ).collect(Collectors.toList());
    public static final ThrowingFunction<List<String>, List<Boolean>> parseBooleans = ints ->
            ints.stream().map( parseBoolean ).collect(Collectors.toList());

    // Filter list
    public static final ThrowingFunction<Predicate<String>, ThrowingFunction<List<String>, List<String>>> filterElements = pred -> list ->
            list.stream().filter( pred ).collect(Collectors.toList());


    // Optional-returning property getter
    private static Optional<String> getProperty(String key, Properties properties) {
        if (properties == null) {
            return Optional.empty();
        }
        String val = properties.getProperty(key);
        return Optional.ofNullable( (val == null || val.isEmpty()) ? null : val );
    }

    public static <T> Optional<T> getProperty(String key, Properties properties, Function<String, ? extends T> func) {
        return getProperty(key, properties).map(func);
    }

    public static <T> Optional<T> getProperty(String key,
                                              Properties properties,
                                              ThrowingFunction<String, ? extends T> func) {
        return getProperty(key, properties)
                .map( val -> {
                    try {
                        return func.apply(val);
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

}
