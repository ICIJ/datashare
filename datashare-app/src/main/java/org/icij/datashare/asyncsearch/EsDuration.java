package org.icij.datashare.asyncsearch;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Elasticsearch time-value strings (e.g. "5m", "30s", "1h", "2d",
 * "500ms") into a {@link Duration}. Returns the fallback for null, blank, or
 * unparseable input.
 */
public final class EsDuration {
    // "ms" must precede "s" so the alternation matches two-char units first.
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");

    private EsDuration() {}

    public static Duration parse(String value, Duration fallback) {
        if (value == null) {
            return fallback;
        }
        Matcher matcher = PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return fallback;
        }
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> fallback;
        };
    }
}
