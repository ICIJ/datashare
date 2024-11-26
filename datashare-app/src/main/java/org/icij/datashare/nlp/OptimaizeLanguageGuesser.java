package org.icij.datashare.nlp;

import com.google.inject.Singleton;
import com.optimaize.langdetect.LanguageDetector;
import com.optimaize.langdetect.LanguageDetectorBuilder;
import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfileReader;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObjectFactory;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.LanguageGuesser;

import java.io.IOException;

@Singleton
public class OptimaizeLanguageGuesser implements LanguageGuesser {
    private final LanguageDetector languageDetector;

    public OptimaizeLanguageGuesser() throws IOException {
        this.languageDetector = LanguageDetectorBuilder.create(NgramExtractors.standard())
                        .withProfiles(new LanguageProfileReader().readAllBuiltIn())
                        .build();
    }

    @Override
    public Language guess(String text) {
        TextObjectFactory textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();
        return Language.parse(languageDetector.detect(textObjectFactory.forText(text)).or(LdLocale.fromString("en")).getLanguage());
    }
}
