package org.icij.datashare.tabular;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAccessor;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The date patterns a mapping declares, compiled when they are declared rather than on the first row,
 * and the one conversion they drive: a cell written the pattern's way becomes the ISO form the target
 * model reads. Pinned to {@link Locale#ROOT}, so a month name reads the same wherever the run happens.
 */
class DateFormats {
    private final Map<String, DateTimeFormatter> formats = new HashMap<>();

    // A two-digit year resolves against 2000, so 'dd/MM/yy' would store 01/01/50 as 2050 and a
    // mapping has no way to say otherwise. Refusing the pattern beats storing a date a century out.
    void declare(String pattern) {
        if (formats.containsKey(pattern)) {
            return;
        }
        if (twoDigitYear(pattern)) {
            throw new IllegalArgumentException("a two-digit year is ambiguous, write the year in full");
        }
        formats.put(pattern, DateTimeFormatter.ofPattern(pattern, Locale.ROOT));
    }

    /**
     * The cell in ISO form, or null when the declared pattern cannot read it: either it does not
     * parse at all, or it parses only because the resolver moved the date (31/02 reads as the 28th),
     * which would store a date the document never carried.
     */
    String iso(String cell, String pattern) {
        DateTimeFormatter format = formats.get(pattern);
        try {
            Temporal date = date(format.parse(cell));
            return format.format(date).equals(cell) ? date.toString() : null;
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

    private static boolean twoDigitYear(String pattern) {
        boolean quoted = false;
        int run = 0;
        for (int index = 0; index <= pattern.length(); index++) {
            char letter = index < pattern.length() ? pattern.charAt(index) : ' ';
            quoted = letter == '\'' ? !quoted : quoted;
            if (!quoted && (letter == 'y' || letter == 'u')) {
                run++;
            } else {
                if (run == 2) {
                    return true;
                }
                run = 0;
            }
        }
        return false;
    }
}
