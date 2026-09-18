package org.icij.datashare.nlp;

import com.github.pemistahl.lingua.api.IsoCode639_1;
import org.icij.datashare.text.Language;
import org.junit.Test;
import java.nio.file.Paths;

import static org.fest.assertions.Assertions.assertThat;

/**
 * Detection never invents a language: letterless or empty input stays UNKNOWN, content always wins
 * over the file name, and no input makes the detector spin.
 */
public class LinguaLanguageGuesserTest {
    private static final LinguaLanguageGuesser GUESSER = new LinguaLanguageGuesser();

    @Test(timeout = 30000)
    public void test_detects_english_prose() {
        assertThat(GUESSER.guess("The quick brown fox jumps over the lazy dog near the river bank every morning."))
                .isEqualTo(Language.ENGLISH);
    }

    @Test(timeout = 30000)
    public void test_detects_french_prose() {
        assertThat(GUESSER.guess("Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee."))
                .isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_detects_short_text() {
        assertThat(GUESSER.guess("Bonjour, comment allez-vous ?")).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_empty_and_blank_text_is_unknown() {
        assertThat(GUESSER.guess("")).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess("   \n\t ")).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess(null)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_letterless_gibberish_is_unknown_not_english() {
        // the optimaize guesser silently returned ENGLISH here: the bug behind the wrongly-english corpus
        assertThat(GUESSER.guess("1234567890 42 -- !!! ??? ... 2026/09/17 +33 06 07")).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_samples_beyond_the_head_of_large_documents() {
        // head full of URL boilerplate, real text past the midpoint: a head-only sample misdetects this
        assertThat(GUESSER.guess(urlBoilerplateThenFrench())).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 60000)
    public void test_every_lingua_iso_code_maps_to_a_datashare_language() {
        for (IsoCode639_1 isoCode : IsoCode639_1.values()) {
            if (isoCode == IsoCode639_1.NONE) {
                continue;
            }
            assertThat(Language.parse(isoCode.toString())).as(isoCode.name()).isNotEqualTo(Language.UNKNOWN);
        }
    }

    @Test(timeout = 30000)
    public void test_guesses_from_the_file_name_when_there_is_no_content() {
        assertThat(GUESSER.guess("", Paths.get("/tmp/Contrat_de_travail_et_conditions_generales.pdf")))
                .isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_content_wins_over_the_file_name() {
        assertThat(GUESSER.guess("Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee.",
                                 Paths.get("/tmp/annual_report_summary_of_the_year.pdf"))).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_short_file_names_stay_unknown() {
        assertThat(GUESSER.guess("", Paths.get("/tmp/IMG_20240101_123456.jpg"))).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess("", Paths.get("/tmp/scan.pdf"))).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess("", null)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_does_not_spin_on_giant_single_line_alphanumeric() {
        // the 30s timeout is the regression guard: optimaize backtracked here forever
        assertThat(GUESSER.guess(giantSingleLine())).isNotNull();
    }

    private static String urlBoilerplateThenFrench() {
        StringBuilder text = new StringBuilder();
        while (text.length() < LinguaLanguageGuesser.MAX_DETECTION_LENGTH * 2) {
            text.append("https://example.org/a/b/c?d=e&f=g http://foo.bar/baz#qux ");
        }
        for (int i = 0; i < 400; i++) {
            text.append("Le petit chat noir dort paisiblement sur le canapé près de la fenêtre ensoleillée. ");
        }
        return text.toString();
    }

    private static String giantSingleLine() {
        StringBuilder text = new StringBuilder(5_000_000);
        for (int i = 0; i < 5_000_000; i++) {
            text.append((char) ('a' + (i % 26)));
        }
        return text.toString();
    }
}
