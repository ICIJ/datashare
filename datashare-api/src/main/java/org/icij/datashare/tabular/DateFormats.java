package org.icij.datashare.tabular;

import java.text.ParsePosition;
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
    // A leap day, so the round-trip in compile() exercises the strictest date the calendar has.
    private static final LocalDateTime PROBE = LocalDateTime.of(2004, 2, 29, 15, 45, 58);

    // Year.toString is unpadded ('70' for year 70), unlike every other Temporal: 'uuuu' prints the
    // ISO form the others print, four digits minimum, '-' for BCE and '+' past 9999.
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("uuuu", Locale.ROOT);

    private final Map<String, DateTimeFormatter> formats = new HashMap<>();

    void declare(String pattern) {
        formats.computeIfAbsent(pattern, DateFormats::compile);
    }

    /** The cell in ISO form, or null when the declared pattern cannot read it. */
    String iso(String cell, String pattern) {
        DateTimeFormatter format = formats.get(pattern);
        // Text the pattern cannot even lex is ordinary data ('n/a' in a date column), not worth a
        // stack-trace-filling exception per cell: the throwing path is kept for text that lexes but
        // does not resolve, like 31/02 under strict resolution.
        ParsePosition position = new ParsePosition(0);
        if (format.parseUnresolved(cell, position) == null || position.getIndex() < cell.length()) {
            return null;
        }
        try {
            Temporal date = date(format.parse(cell));
            return date instanceof Year year ? YEAR.format(year) : date.toString();
        } catch (DateTimeException unreadable) {
            return null;
        }
    }

    // Compiling proves syntax only: 'HH:mm' carries no date at all, and an offset is a precision
    // date() drops. One round-trip over a fixed date turns "fails on every row" into a refusal at
    // declare time, which validate() reports as an unusable format.
    private static DateTimeFormatter compile(String pattern) {
        String rewritten = strict(pattern);
        DateTimeFormatter format;
        try {
            format = DateTimeFormatter.ofPattern(rewritten, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
        } catch (IllegalArgumentException malformed) {
            throw new UnusableDateFormat(pattern, "is not a date pattern: " + malformed.getMessage());
        }
        try {
            date(format.parse(format.format(PROBE)));
        } catch (DateTimeException undatable) {
            throw new UnusableDateFormat(pattern, "cannot turn a cell into a date the model stores");
        }
        return format;
    }

    // The precision the pattern actually carries: reading every cell as a date would drop the time a
    // datetime column declares, and refuse the year or year-and-month the target model accepts.
    private static Temporal date(TemporalAccessor parsed) {
        if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
            throw new DateTimeException("an offset is a precision the target model does not store");
        }
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

    // The rewrite strict parsing needs. A week-based field ('Y', 'w', 'W')
    // never resolves to a date, so a pattern carrying one would read no cell at all. And 'y' is
    // year-of-era, which STRICT refuses to resolve without an era, so it is rewritten to the
    // proleptic 'u' unless the pattern carries a 'G': rewritten next to an era, every BC year would
    // silently read as CE.
    private static String strict(String pattern) {
        char[] letters = unquoted(pattern);
        boolean era = new String(letters).indexOf('G') >= 0;
        StringBuilder rewritten = new StringBuilder(pattern.length());
        int run = 0;
        for (int index = 0; index < letters.length; index++) {
            char letter = letters[index];
            if (letter == 'Y' || letter == 'w' || letter == 'W') {
                throw new UnusableDateFormat(pattern,
                        "carries the week-based '" + letter + "', which never resolves to a date");
            }
            if (letter == 'u' && era) {
                throw new UnusableDateFormat(pattern,
                        "holds a proleptic year 'u' next to an era 'G', which parses the era and ignores it, use 'y'");
            }
            boolean year = letter == 'y' || letter == 'u';
            if (!year) {
                requireFullYear(pattern, run);
            }
            run = year ? run + 1 : 0;
            rewritten.append(year && !era ? 'u' : pattern.charAt(index));
        }
        requireFullYear(pattern, run);
        return rewritten.toString();
    }

    // Quoted text is literal, so it is blanked out before anything looks for a pattern letter: the
    // era scan and the rewrite then cannot disagree about what a quote covers.
    private static char[] unquoted(String pattern) {
        char[] letters = pattern.toCharArray();
        boolean quoted = false;
        for (int index = 0; index < letters.length; index++) {
            boolean quote = letters[index] == '\'';
            quoted = quote != quoted;
            if (quoted || quote) {
                letters[index] = ' ';
            }
        }
        return letters;
    }

    // 'yy' resolves against 2000 and a lone 'y' reads '70' as the first century: either stores a
    // date a century out and a mapping has no way to say otherwise, so refusing the pattern beats
    // storing the corruption.
    private static void requireFullYear(String pattern, int run) {
        if (run == 1 || run == 2) {
            throw new UnusableDateFormat(pattern, "has a one- or two-digit year, write the year in full");
        }
    }
}
