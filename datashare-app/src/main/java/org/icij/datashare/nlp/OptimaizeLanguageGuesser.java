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
    // Bounds UrlTextFilter's O(n^2) regex, which otherwise spins forever on multi-MB single-line
    // blobs (minified JS, one-line JSON): optimaize runs its filters over the WHOLE input before
    // truncating to its own detection window, so we must cap the raw input ourselves first.
    // Tradeoff: detection now uses the first 10k raw chars rather than the first 10k of the
    // fully filtered text, so a document whose first 10k chars are dominated by URLs/emails may
    // detect less accurately. Acceptable: real documents carry detectable text within 10k chars.
    static final int MAX_DETECTION_LENGTH = 10_000;

    private final LanguageDetector languageDetector;
    private final TextObjectFactory textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText();

    public OptimaizeLanguageGuesser() throws IOException {
        this.languageDetector = LanguageDetectorBuilder.create(NgramExtractors.standard())
                        .withProfiles(new LanguageProfileReader().readAllBuiltIn())
                        .build();
    }

    @Override
    public Language guess(String text) {
        String sample = text.length() > MAX_DETECTION_LENGTH ? text.substring(0, MAX_DETECTION_LENGTH) : text;
        return Language.parse(languageDetector.detect(textObjectFactory.forText(sample)).or(LdLocale.fromString("en")).getLanguage());
    }
}
