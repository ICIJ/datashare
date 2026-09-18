package org.icij.datashare.text.indexing;

import org.icij.datashare.text.Language;
import java.nio.file.Path;
import java.util.regex.Pattern;

public interface LanguageGuesser {
    /**
     * Under this many letters a file name is mostly a scanner or camera code ("IMG_20240101"), and
     * detecting on it returns a confident wrong language instead of no answer.
     */
    int MIN_FILENAME_LENGTH = 15;

    /** Runs of digits, separators and punctuation, which a file name uses where a sentence uses spaces. */
    Pattern NON_LETTER_RUN = Pattern.compile("[^\\p{L}]+");

    Language guess(String text);

    /**
     * The guess for a document whose content can be empty: a scan that has not been OCRed yet carries
     * no text, so its file name is the only language signal left. Content always wins, and a file name
     * carrying fewer than {@link #MIN_FILENAME_LENGTH} letters stays {@link Language#UNKNOWN} rather
     * than being guessed on.
     */
    default Language guess(String text, Path path) {
        Language language = guess(text);
        if (language != Language.UNKNOWN || path == null || path.getFileName() == null) {
            return language;
        }
        String words = wordsFromFilename(path.getFileName().toString());
        return words.length() < MIN_FILENAME_LENGTH ? Language.UNKNOWN : guess(words);
    }

    private static String wordsFromFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        String withoutExtension = dot > 0 ? filename.substring(0, dot) : filename;
        return NON_LETTER_RUN.matcher(withoutExtension).replaceAll(" ").trim();
    }
}
