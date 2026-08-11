package org.icij.datashare.text;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Counts a query's occurrences the way {@code /documents/searchContent} does, so a search over
 * artifacts folds text exactly as Elasticsearch folds it. Every rule here is a port of
 * {@code datashare-index/src/main/resources/searchOccurrences.painless.java}, quirks included:
 * the two search the same documents in the same UI, so a rule improved on one side alone shows up
 * as two counters disagreeing about one word. Each quirk is pinned by a test.
 */
public class ContentOccurrences {
    /**
     * Below this, a char is NFKD-stable and carries no diacritic, so both branches of the script's
     * fold produce the char itself: skipping the Normalizer there is a speed-up, not a change of
     * meaning. It does not reach Cyrillic or Greek, whose letters are all LOWERCASE_LETTER, so a
     * large document in those scripts still pays one Normalizer call per letter. Bounded by
     * StructureMarkdownExtractor's 16M-char output cap, and the first thing to measure if an
     * in-document search ever feels slow.
     */
    private static final char ASCII_LIMIT = 0x80;

    private ContentOccurrences() {}

    /** Non-overlapping occurrences of {@code query} in {@code content}, both folded first. */
    public static int count(String content, String query) {
        // The script's loop never advances on an empty query, so it would spin forever. Serving
        // code rejects a blank query before calling, but a hang is too sharp an edge to leave here.
        if (query.isEmpty()) {
            return 0;
        }
        return occurrences(fold(content), fold(query), query.length());
    }

    /**
     * Lowercased, then NFKD-normalized and stripped of non-spacing marks for {@code LOWERCASE_LETTER}
     * chars only. Everything else passes through, which is why a decomposed accent survives and a
     * caseless script is untouched: {@code normalizeLetters} tests that one type and nothing else.
     * {@link Locale#ROOT} rather than the default, so folding cannot depend on where the JVM runs;
     * the script's own {@code toLowerCase()} takes the Elasticsearch node's default.
     */
    static String fold(String input) {
        StringBuilder folded = new StringBuilder(input.length());
        for (char character : input.toLowerCase(Locale.ROOT).toCharArray()) {
            appendFolded(folded, character);
        }
        return folded.toString();
    }

    // Steps by the raw query's length, as the script does, not by the folded one: a query whose
    // fold expands (a ligature) then finds overlapping matches, in Elasticsearch today and here.
    private static int occurrences(String content, String query, int step) {
        int count = 0;
        for (int at = content.indexOf(query); at != -1; at = content.indexOf(query, at + step)) {
            count++;
        }
        return count;
    }

    private static void appendFolded(StringBuilder folded, char character) {
        if (character < ASCII_LIMIT || Character.getType(character) != Character.LOWERCASE_LETTER) {
            folded.append(character);
            return;
        }
        appendWithoutMarks(folded, Normalizer.normalize(String.valueOf(character), Normalizer.Form.NFKD));
    }

    private static void appendWithoutMarks(StringBuilder folded, String normalized) {
        for (char character : normalized.toCharArray()) {
            if (Character.getType(character) != Character.NON_SPACING_MARK) {
                folded.append(character);
            }
        }
    }
}
