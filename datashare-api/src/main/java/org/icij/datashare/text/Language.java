package org.icij.datashare.text;

import java.util.Locale;
import java.util.Optional;

/**
 * Created by julien on 3/30/16.
 */
public enum Language {
    ENGLISH    ("eng", "en"),
    SPANISH    ("spa", "es"),
    GERMAN     ("deu", "de"),
    FRENCH     ("fra", "fr"),
    RUSSIAN    ("rus", "ru"),
    CHINESE    ("zho", "zh"),
    PORTUGUESE ("por", "pt"),
    ITALIAN    ("ita", "it"),
    POLISH     ("pol", "pl"),
    DUTCH      ("nld", "nl"),
    ARABIC     ("ara", "ar"),
    GALICIAN   ("glg", "gl"),
    CATALAN    ("cat", "ca"),
    SWEDISH    ("swe", "sv"),
    ROMANIAN   ("ron", "ro"),
    HUNGARIAN  ("hun", "hu"),
    DANISH     ("dan", "da"),
    SLOVAK     ("slk", "sk"),
    BASQUE     ("eus", "eu"),
    LITHUANIAN ("lit", "lt"),
    NORWEGIAN  ("nor", "no"),
    SLOVENIAN  ("slv", "sl"),
    ESTONIAN   ("est", "et"),
    BELARUSIAN ("bel", "be"),
    ICELANDIC  ("isl", "is"),
    NONE       ("none",    "none"),
    UNKNOWN    ("unknown", "unknown");

    private final String iso6391Code;
    private final String iso6392Code;

    Language(final String iso2Code, final String iso1Code) {
        iso6392Code = iso2Code;
        iso6391Code = iso1Code;
    }

    public String getISO6392Code() { return iso6392Code; }

    public String getISO6391Code() { return iso6391Code; }

    @Override
    public String toString() { return getISO6391Code(); }


    public static Optional<Language> parse(final String language) {
        if (    language == null ||
                language.isEmpty() ||
                language.equalsIgnoreCase(NONE.toString()) ||
                language.equalsIgnoreCase(UNKNOWN.toString())) {
            return Optional.empty();
        }

        for (Language lang : Language.values()) {
            if (language.equalsIgnoreCase(lang.toString()) || language.equalsIgnoreCase(lang.getISO6392Code())) {
                return Optional.of(lang);
            }
        }

        try {
            return Optional.of(valueOf(language.toUpperCase(Locale.ROOT)));

        } catch (IllegalArgumentException e) {
            // throw new IllegalArgumentException(String.format("\"%s\" is not a valid language code.", language));
            return Optional.empty();
        }
    }

}
