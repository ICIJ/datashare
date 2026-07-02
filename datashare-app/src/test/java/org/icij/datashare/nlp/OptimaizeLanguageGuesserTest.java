package org.icij.datashare.nlp;

import org.icij.datashare.text.Language;
import org.junit.Test;

import java.io.IOException;

import static org.fest.assertions.Assertions.assertThat;

public class OptimaizeLanguageGuesserTest {
    private final OptimaizeLanguageGuesser guesser = new OptimaizeLanguageGuesser();

    public OptimaizeLanguageGuesserTest() throws IOException {}

    @Test(timeout = 5000)
    public void test_does_not_spin_on_giant_single_line_alphanumeric() {
        StringBuilder sb = new StringBuilder(5_000_000);
        for (int i = 0; i < 5_000_000; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        // Before the cap this backtracks in UrlTextFilter forever; the timeout guards the regression.
        assertThat(guesser.guess(sb.toString())).isNotNull();
    }

    @Test
    public void test_detects_english_prose() {
        assertThat(guesser.guess("The quick brown fox jumps over the lazy dog near the river bank every morning."))
                .isEqualTo(Language.ENGLISH);
    }

    @Test
    public void test_detects_french_prose() {
        assertThat(guesser.guess("Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee."))
                .isEqualTo(Language.FRENCH);
    }

    @Test
    public void test_detects_language_from_prefix_when_longer_than_cap() {
        String sentence = "The quick brown fox jumps over the lazy dog near the river bank every morning. ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < OptimaizeLanguageGuesser.MAX_DETECTION_LENGTH * 2) {
            sb.append(sentence);
        }
        assertThat(guesser.guess(sb.toString())).isEqualTo(Language.ENGLISH);
    }

    @Test
    public void test_empty_text_falls_back_to_english() {
        assertThat(guesser.guess("")).isEqualTo(Language.ENGLISH);
    }
}
