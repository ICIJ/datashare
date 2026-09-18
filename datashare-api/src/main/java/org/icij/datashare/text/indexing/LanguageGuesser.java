package org.icij.datashare.text.indexing;

import java.nio.file.Path;
import org.icij.datashare.text.Language;

public interface LanguageGuesser {
    // Under this many characters a file name is mostly a scanner or camera code ("IMG_20240101"),
    // and detecting on it returns a confident wrong language instead of no answer.
    int MIN_FILENAME_LENGTH = 15;

    Language guess(String text);

    // A scan that has not been OCRed yet carries no text: its file name is the only language signal.
    default Language guess(String text, Path path) {
        Language language = guess(text);
        if (language != Language.UNKNOWN || path == null || path.getFileName() == null) {
            return language;
        }
        String words = words(path.getFileName().toString());
        return words.length() < MIN_FILENAME_LENGTH ? Language.UNKNOWN : guess(words);
    }

    private static String words(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename).replaceAll("[^\\p{L}]+", " ").trim();
    }
}
