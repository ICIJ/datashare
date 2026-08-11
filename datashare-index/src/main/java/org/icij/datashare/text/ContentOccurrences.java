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
     * meaning, and it is the branch nearly every character of a Latin-script page takes.
     */
    private static final char ASCII_LIMIT = 0x80;

    // Latin Extended, Greek and Cyrillic all sit below this and are all LOWERCASE_LETTER, so they miss
    // the fast path: a full Russian page costs 17x an ASCII one without the memo. Racy on purpose, no
    // lock: a String is safely published by its final fields, so a reader sees null or the same value.
    private static final int MEMO_LIMIT = 0x600;
    private static final String[] MEMO = new String[MEMO_LIMIT];

    private ContentOccurrences() {}

    /**
     * Occurrences of {@code query} in {@code content}, both folded first. Stepping by the raw query's
     * length, as the script does, means matches may overlap when the fold expands the query (a
     * ligature): {@code count("afffb", "ﬀ")} is 2, and the two share a character.
     */
    public static int count(String content, String query) {
        // On the folded query, which is what drives the loop: indexOf never returns -1 for an empty
        // needle, so an empty one spins forever rather than miscounting. Nothing folds to empty today,
        // so guarding the raw query would leave termination resting on that, one edit away.
        String foldedQuery = fold(query);
        if (foldedQuery.isEmpty()) {
            return 0;
        }
        return occurrences(fold(content), foldedQuery, query.length());
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
        if (character >= MEMO_LIMIT) {
            folded.append(withoutMarks(character));
            return;
        }
        String memoized = MEMO[character];
        folded.append(memoized != null ? memoized : (MEMO[character] = withoutMarks(character)));
    }

    private static String withoutMarks(char original) {
        String normalized = Normalizer.normalize(String.valueOf(original), Normalizer.Form.NFKD);
        StringBuilder kept = new StringBuilder(normalized.length());
        for (char character : normalized.toCharArray()) {
            if (Character.getType(character) != Character.NON_SPACING_MARK) {
                kept.append(character);
            }
        }
        return kept.toString();
    }
}
