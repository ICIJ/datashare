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
    private final LanguageDetector languageDetector = LanguageDetectorBuilder.fromAllSpokenLanguages().build();

    @Override
    public Language guess(String text) {
        if (text == null || text.isBlank()) {
            return Language.UNKNOWN;
        }
        com.github.pemistahl.lingua.api.Language detected = languageDetector.detectLanguageOf(sample(text));
        return detected == com.github.pemistahl.lingua.api.Language.UNKNOWN ? Language.UNKNOWN :
               Language.parse(detected.getIsoCode639_1().toString());
    }

    // Three slices spread across the document: a head-only sample misdetects documents whose first
    // pages are URLs, headers or other boilerplate.
    private static String sample(String text) {
        if (text.length() <= MAX_DETECTION_LENGTH) {
            return text;
        }
        int slice = MAX_DETECTION_LENGTH / 3;
        int mid = (int) (text.length() * 0.45);
        int end = (int) (text.length() * 0.90);
        return text.substring(0, slice)
                + text.substring(mid, mid + slice)
                + text.substring(end, Math.min(end + slice, text.length()));
    }
}
