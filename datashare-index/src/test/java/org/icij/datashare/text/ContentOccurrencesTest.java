package org.icij.datashare.text;

import org.junit.Test;

import java.util.Locale;

import static org.fest.assertions.Assertions.assertThat;

/** Pins the folding and counting rules ContentOccurrences ports from
 *  searchOccurrences.painless.java: every test here fails if the two drift apart. */
public class ContentOccurrencesTest {
    @Test
    public void test_matching_ignores_case() {
        assertThat(ContentOccurrences.count("Data and DATA", "data")).isEqualTo(2);
    }

    // Escapes rather than literal accented characters throughout this class: these tests turn
    // on whether an accent is precomposed or decomposed, which a literal does not show and an
    // editor can silently renormalize, inverting the next test without touching its source.
    @Test
    public void test_a_precomposed_accent_folds_to_its_base_letter() {
        // "é" as U+00E9 is a LOWERCASE_LETTER, so the script normalizes it and drops the mark.
        assertThat(ContentOccurrences.count("r\u00e9sum\u00e9", "resume")).isEqualTo(1);
    }

    @Test
    public void test_a_decomposed_accent_does_not_fold() {
        // The same word written "e" + U+0301: the combining acute is NON_SPACING_MARK, not
        // LOWERCASE_LETTER, so the script's else branch keeps it and the mark breaks the match.
        // Wrong on its face, and load-bearing: "fixing" it makes this search disagree with
        // /documents/searchContent on the same document.
        assertThat(ContentOccurrences.count("re\u0301sume", "resume")).isEqualTo(0);
    }

    @Test
    public void test_a_caseless_script_is_left_untouched() {
        // Arabic letters are OTHER_LETTER, so no NFKD runs: alef-with-hamza (U+0623) never
        // decomposes into a bare alef (U+0627), so the two spellings of "ahmad" stay as
        // distinct here as they are in Elasticsearch.
        assertThat(ContentOccurrences.count("\u0623\u062d\u0645\u062f", "\u0623\u062d\u0645\u062f")).isEqualTo(1);
        assertThat(ContentOccurrences.count("\u0623\u062d\u0645\u062f", "\u0627\u062d\u0645\u062f")).isEqualTo(0);
    }

    @Test
    public void test_matches_do_not_overlap() {
        // The script steps by the query's length, so "aaaa" holds two "aa", not three.
        assertThat(ContentOccurrences.count("aaaa", "aa")).isEqualTo(2);
    }

    @Test
    public void test_the_query_is_matched_literally_not_as_a_regex() {
        // A regex would match "abc" too, and every user typing a dot would get a wrong count.
        assertThat(ContentOccurrences.count("a.c and abc", "a.c")).isEqualTo(1);
    }

    @Test
    public void test_a_compatibility_ligature_expands_when_folded() {
        // NFKD is the compatibility form, so "ﬁ" (U+FB01) becomes "fi" and the folded string
        // outgrows its source. Harmless for a count, and exactly why the script's offsets drift,
        // which is why offsets are not part of this endpoint's contract.
        assertThat(ContentOccurrences.count("of\ufb01ce", "office")).isEqualTo(1);
    }

    @Test
    public void test_an_empty_query_counts_nothing_instead_of_hanging() {
        // The script's loop never advances on an empty query. Serving code rejects a blank one,
        // so this guards the library against its next caller, not the route.
        assertThat(ContentOccurrences.count("anything", "")).isEqualTo(0);
    }

    @Test
    public void test_ascii_folds_to_lowercase_and_nothing_else() {
        // The ASCII fast path must produce exactly what the script's two branches produce.
        assertThat(ContentOccurrences.fold("Hello, World! 42")).isEqualTo("hello, world! 42");
    }

    @Test
    public void test_folding_ignores_the_jvm_default_locale() {
        Locale previous = Locale.getDefault();
        try {
            // Under tr-TR, "I".toLowerCase() is the dotless "ı", which would stop INDEX from
            // matching index. Locale.ROOT pins the fold so a deployment's locale cannot change
            // what a search finds.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(ContentOccurrences.count("INDEX", "index")).isEqualTo(1);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
