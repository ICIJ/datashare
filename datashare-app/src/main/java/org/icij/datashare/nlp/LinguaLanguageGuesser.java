package org.icij.datashare.nlp;

import com.github.pemistahl.lingua.api.LanguageDetector;
import com.github.pemistahl.lingua.api.LanguageDetectorBuilder;
import com.google.inject.Singleton;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.LanguageGuesser;

@Singleton
public class LinguaLanguageGuesser implements LanguageGuesser {
    // Bounds per-document detection CPU on multi-MB content; 10k chars is plenty for n-gram detection.
    static final int MAX_DETECTION_LENGTH = 10_000;
    // Low accuracy mode keeps the lazily-built n-gram models near 50MB: the high accuracy path loads
    // 1..5-grams for all 75 languages on the first short text and pins ~1.1GB for the process lifetime.
    private final LanguageDetector languageDetector =
            LanguageDetectorBuilder.fromAllSpokenLanguages().withLowAccuracyMode().build();

    @Override
    public Language guess(String text) {
        if (text == null || text.isBlank()) {
            return Language.UNKNOWN;
        }
        // lingua answers its own UNKNOWN with the ISO code "NONE", which Language.parse maps back to UNKNOWN.
        return Language.parse(languageDetector.detectLanguageOf(sample(text)).getIsoCode639_1().toString());
    }

    private static String sample(String text) {
        return text.length() <= MAX_DETECTION_LENGTH ? text : text.substring(0, MAX_DETECTION_LENGTH);
    }
}
