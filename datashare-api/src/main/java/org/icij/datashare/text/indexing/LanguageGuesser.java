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
    /**
     * Scripts written by a single one of the languages we detect, where latin letters need a whole
     * sentence to name theirs. Cyrillic, arabic and devanagari are left out: they are shared by
     * several languages, and a short name in them detects as the wrong one.
     */
    Pattern SELF_NAMING_SCRIPT = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}\\p{IsThai}\\p{IsGreek}"
            + "\\p{IsHebrew}\\p{IsArmenian}\\p{IsGeorgian}\\p{IsTamil}\\p{IsTelugu}"
            + "\\p{IsBengali}\\p{IsGujarati}\\p{IsGurmukhi}]");

    Language guess(String text);

    /**
     * The guess for a document whose content can be empty: a scan that has not been OCRed yet carries
     * no text, so its file name is the only language signal left. Content always wins, and a file name
     * carrying fewer than {@link #MIN_FILENAME_LENGTH} letters stays {@link Language#UNKNOWN} rather
     * than being guessed on, unless it is written in a script that identifies itself.
     */
    default Language guess(String text, Path path) {
        Language language = guess(text);
        if (language != Language.UNKNOWN || path == null || path.getFileName() == null) {
            return language;
        }
        String words = NON_LETTER_RUN.matcher(withoutExtension(path.getFileName().toString())).replaceAll(" ").trim();
        boolean longEnough = words.length() >= MIN_FILENAME_LENGTH || SELF_NAMING_SCRIPT.matcher(words).find();
        return longEnough ? guess(words) : Language.UNKNOWN;
    }

    /** The extension is latin whatever the name is written in, and it drags the guess towards latin. */
    private static String withoutExtension(String fileName) {
        int extension = fileName.lastIndexOf('.');
        return extension > 0 ? fileName.substring(0, extension) : fileName;
    }
}
