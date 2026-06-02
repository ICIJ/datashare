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
    @Test public void test_every_builtin_language_code_parses_without_throwing() throws IOException {
        List<LanguageProfile> profiles = new LanguageProfileReader().readAllBuiltIn();
        assertThat(profiles).isNotEmpty();
        for (LanguageProfile profile : profiles) {
            LdLocale locale = profile.getLocale();
            // must not throw IllegalArgumentException for any built-in code
            Language parsed = Language.parse(locale.getLanguage());
            assertThat(parsed).isNotNull();
        }
    }
}
