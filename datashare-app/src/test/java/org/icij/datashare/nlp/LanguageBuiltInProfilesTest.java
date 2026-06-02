package org.icij.datashare.nlp;

import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import org.icij.datashare.text.Language;
import org.junit.Test;

import java.io.IOException;
import java.util.List;

import static org.fest.assertions.Assertions.assertThat;

public class LanguageBuiltInProfilesTest {
    @Test public void test_guesser_maps_every_builtin_code_without_throwing() throws IOException {
        List<LanguageProfile> profiles = new LanguageProfileReader().readAllBuiltIn();
        assertThat(profiles).isNotEmpty();
        for (LanguageProfile profile : profiles) {
            LdLocale locale = profile.getLocale();
            // The guesser must never abort extraction on a detected code, even one the enum lacks.
            Language mapped = OptimaizeLanguageGuesser.toLanguage(locale.getLanguage());
            assertThat(mapped).isNotNull();
        }
    }

    @Test public void test_unsupported_detected_code_degrades_to_unknown() {
        // Asturian ("ast") is a built-in Optimaize profile but absent from the Language enum.
        assertThat(OptimaizeLanguageGuesser.toLanguage("ast")).isEqualTo(Language.UNKNOWN);
    }

    @Test public void test_supported_detected_code_maps_to_language() {
        assertThat(OptimaizeLanguageGuesser.toLanguage("en")).isEqualTo(Language.ENGLISH);
    }
}
