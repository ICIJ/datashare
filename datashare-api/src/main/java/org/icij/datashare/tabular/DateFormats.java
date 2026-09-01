package org.icij.datashare.tabular;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The date patterns a mapping declares, compiled when they are declared rather than on the first row,
 * and the one conversion they drive: a cell written the pattern's way becomes the ISO form the target
 * model reads. Pinned to {@link Locale#ROOT}, so a month name reads the same wherever the run happens,
 * and resolved strictly, so a date the document never carried (31/02 smeared to the 28th) is refused
 * rather than stored.
 */
class DateFormats {
    private final Map<String, DateTimeFormatter> formats = new HashMap<>();

    void declare(String pattern) {
        formats.computeIfAbsent(pattern, declared -> DateTimeFormatter
                .ofPattern(strict(declared), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT));
    }

    /** The cell in ISO form, or null when the declared pattern cannot read it. */
    String iso(String cell, String pattern) {
        try {
            Temporal date = date(formats.get(pattern).parse(cell));
            // Year.toString is unpadded ('70' for year 70), unlike every other Temporal.
            return date instanceof Year year ? String.format("%04d", year.getValue()) : date.toString();
        } catch (DateTimeException unreadable) {
            return null;
        }
    }

    // The precision the pattern actually carries: reading every cell as a date would drop the time a
    // datetime column declares, and refuse the year or year-and-month the target model accepts.
    private static Temporal date(TemporalAccessor parsed) {
        if (parsed.isSupported(ChronoField.HOUR_OF_DAY)) {
            return LocalDateTime.from(parsed);
        }
        if (parsed.isSupported(ChronoField.DAY_OF_MONTH)) {
            return LocalDate.from(parsed);
        }
        if (parsed.isSupported(ChronoField.MONTH_OF_YEAR)) {
            return YearMonth.from(parsed);
        }
        return Year.from(parsed);
    }

    // One quote-aware pass doing the two jobs strict parsing needs. A two-digit year resolves
    // against 2000, so 'dd/MM/yy' would store 01/01/50 as 2050 and a mapping has no way to say
    // otherwise: refusing the pattern beats storing a date a century out. And 'y' is year-of-era,
    // which STRICT refuses to resolve without an era the pattern cannot carry, so it is rewritten
    // to the proleptic 'u'.
    private static String strict(String pattern) {
        StringBuilder rewritten = new StringBuilder(pattern.length());
        boolean quoted = false;
        int run = 0;
        for (int index = 0; index <= pattern.length(); index++) {
            char letter = index < pattern.length() ? pattern.charAt(index) : ' ';
            if (letter == '\'') {
                quoted = !quoted;
            }
            boolean year = !quoted && (letter == 'y' || letter == 'u');
            if (!year && run == 2) {
                throw new IllegalArgumentException("a two-digit year is ambiguous, write the year in full");
            }
            run = year ? run + 1 : 0;
            if (index < pattern.length()) {
                rewritten.append(year ? 'u' : letter);
            }
        }
        return rewritten.toString();
    }
}
