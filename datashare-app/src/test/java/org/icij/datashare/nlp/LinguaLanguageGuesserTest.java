package org.icij.datashare.nlp;

import org.icij.datashare.text.Language;
import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;

public class LinguaLanguageGuesserTest {
    private static final LinguaLanguageGuesser guesser = new LinguaLanguageGuesser();

    @Test(timeout = 30000)
    public void test_detects_english_prose() {
        assertThat(guesser.guess("The quick brown fox jumps over the lazy dog near the river bank every morning."))
                .isEqualTo(Language.ENGLISH);
    }

    @Test(timeout = 30000)
    public void test_detects_french_prose() {
        assertThat(guesser.guess("Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee."))
                .isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_detects_short_text() {
        assertThat(guesser.guess("Bonjour, comment allez-vous ?")).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_empty_and_blank_text_is_unknown() {
        assertThat(guesser.guess("")).isEqualTo(Language.UNKNOWN);
        assertThat(guesser.guess("   \n\t ")).isEqualTo(Language.UNKNOWN);
        assertThat(guesser.guess(null)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_letterless_gibberish_is_unknown_not_english() {
        // the optimaize guesser silently returned ENGLISH here: the bug behind the wrongly-english corpus
        assertThat(guesser.guess("1234567890 42 -- !!! ??? ... 2026/09/17 +33 06 07")).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_samples_beyond_the_head_of_large_documents() {
        // head full of URL boilerplate, real text past the midpoint: a head-only sample misdetects this
        String sample = "https://example.org/a/b/c?d=e&f=g http://foo.bar/baz#qux ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < LinguaLanguageGuesser.MAX_DETECTION_LENGTH * 2) {
            sb.append(sample);
        }
        String french = "Le petit chat noir dort paisiblement sur le canapé près de la fenêtre ensoleillée. ";
        for (int i = 0; i < 400; i++) {
            sb.append(french);
        }
        assertThat(guesser.guess(sb.toString())).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 60000)
    public void test_every_lingua_language_maps_to_a_datashare_language() {
        for (com.github.pemistahl.lingua.api.Language linguaLanguage :
                com.github.pemistahl.lingua.api.Language.values()) {
            if (linguaLanguage == com.github.pemistahl.lingua.api.Language.UNKNOWN) {
                continue;
            }
            assertThat(Language.parse(linguaLanguage.getIsoCode639_1().toString()))
                    .as(linguaLanguage.name())
                    .isNotEqualTo(Language.UNKNOWN);
        }
    }

    @Test(timeout = 30000)
    public void test_does_not_spin_on_giant_single_line_alphanumeric() {
        StringBuilder sb = new StringBuilder(5_000_000);
        for (int i = 0; i < 5_000_000; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        // the 30s timeout is the regression guard: optimaize backtracked here forever
        assertThat(guesser.guess(sb.toString())).isNotNull();
    }
}
