package org.icij.datashare.nlp;

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
    public void test_detects_from_the_head_of_documents_longer_than_the_cap() {
        assertThat(GUESSER.guess(frenchLongerThanTheCap())).isEqualTo(Language.FRENCH);
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
        assertThat(GUESSER.guess("", Paths.get("/tmp/DSC_0042.jpg"))).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess("", null)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_guesses_from_a_short_file_name_in_a_self_naming_script() {
        assertThat(GUESSER.guess("", Paths.get("/tmp/發票.pdf"))).isEqualTo(Language.CHINESE);
        assertThat(GUESSER.guess("", Paths.get("/tmp/レポート.pdf"))).isEqualTo(Language.JAPANESE);
        assertThat(GUESSER.guess("", Paths.get("/tmp/영수증.pdf"))).isEqualTo(Language.KOREAN);
        assertThat(GUESSER.guess("", Paths.get("/tmp/ใบเสร็จ.pdf"))).isEqualTo(Language.THAI);
        assertThat(GUESSER.guess("", Paths.get("/tmp/έγγραφο.pdf"))).isEqualTo(Language.GREEK);
        assertThat(GUESSER.guess("", Paths.get("/tmp/חשבונית.pdf"))).isEqualTo(Language.HEBREW);
        assertThat(GUESSER.guess("", Paths.get("/tmp/հաշիվ.pdf"))).isEqualTo(Language.ARMENIAN);
        assertThat(GUESSER.guess("", Paths.get("/tmp/ანგარიში.pdf"))).isEqualTo(Language.GEORGIAN);
        assertThat(GUESSER.guess("", Paths.get("/tmp/ரசீது.pdf"))).isEqualTo(Language.TAMIL);
        assertThat(GUESSER.guess("", Paths.get("/tmp/రసీదు.pdf"))).isEqualTo(Language.TELUGU);
        assertThat(GUESSER.guess("", Paths.get("/tmp/রসিদ.pdf"))).isEqualTo(Language.BENGALI);
        assertThat(GUESSER.guess("", Paths.get("/tmp/રસીદ.pdf"))).isEqualTo(Language.GUJARATI);
        assertThat(GUESSER.guess("", Paths.get("/tmp/ਰਸੀਦ.pdf"))).isEqualTo(Language.PANJABI);
    }

    @Test(timeout = 30000)
    public void test_short_file_names_in_a_shared_script_stay_unknown() {
        // the russian "счет" detects as macedonian, the urdu "رسید" as persian: the script names several languages
        assertThat(GUESSER.guess("", Paths.get("/tmp/счет.pdf"))).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess("", Paths.get("/tmp/رسید.pdf"))).isEqualTo(Language.UNKNOWN);
        assertThat(GUESSER.guess("", Paths.get("/tmp/रसीद.pdf"))).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_does_not_spin_on_giant_single_line_alphanumeric() {
        // the 30s timeout is the regression guard: optimaize backtracked here forever
        assertThat(GUESSER.guess(giantSingleLine())).isNotNull();
    }

    private static final String FRENCH_PROSE =
            "Le petit chat noir dort paisiblement sur le canape pres de la fenetre ensoleillee.";

    @Test(timeout = 30000)
    public void test_low_confidence_ocr_text_gives_no_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.3, 60)).isEqualTo(Language.UNKNOWN);
    }

    @Test(timeout = 30000)
    public void test_confident_ocr_text_keeps_its_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.9, 60)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_ocr_confidence_at_the_threshold_is_trusted() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.6, 60)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_ocr_confidence_at_a_float_imprecise_threshold_is_trusted() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.58, 58)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_a_zero_threshold_trusts_every_read() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), 0.0, 0)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_no_ocr_confidence_keeps_the_content_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), null, 60)).isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_low_confidence_ocr_text_falls_back_to_a_long_file_name() {
        assertThat(GUESSER.guess("The quick brown fox jumps over the lazy dog near the river bank every morning.",
                                 Paths.get("/tmp/Contrat_de_travail_et_conditions_generales.png"), 0.3, 60))
                .isEqualTo(Language.FRENCH);
    }

    @Test(timeout = 30000)
    public void test_an_unreadable_ocr_confidence_keeps_the_content_language() {
        assertThat(GUESSER.guess(FRENCH_PROSE, Paths.get("/tmp/IMG_0042.png"), "not a number", 60))
                .isEqualTo(Language.FRENCH);
    }

    private static String frenchLongerThanTheCap() {
        StringBuilder text = new StringBuilder();
        while (text.length() < LinguaLanguageGuesser.MAX_DETECTION_LENGTH * 2) {
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
