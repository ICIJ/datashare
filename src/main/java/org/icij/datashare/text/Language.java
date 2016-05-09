package org.icij.datashare.text;

import java.util.Locale;

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
    LITHUANIAN ("lit", "lt"),
    NORWEGIAN  ("nor", "no"),
    SLOVENIAN  ("slv", "sl"),
    ESTONIAN   ("est", "et"),
    BELARUSIAN ("bel", "be"),
    ICELANDIC  ("isl", "is"),

    ALL        ("all",     "all"),
    NONE       ("none",    "none"),
    UNKNOWN    ("unknown", "unknown");

    private final String iso6391Code;
    private final String iso6392Code;

    Language(String iso2Code, String iso1Code) {
        iso6392Code = iso2Code;
        iso6391Code = iso1Code;
    }

    @Override
    public String toString() { return getISO6391Code(); }

    public String getISO6392Code() { return iso6392Code; }

    public String getISO6391Code() { return iso6391Code; }

    public static Language parse(final String lang) {
        if (lang == null || lang.isEmpty()) {
            return NONE;
        }
        for (Language l : Language.values()) {
            if (lang.equalsIgnoreCase(l.toString()) || lang.equalsIgnoreCase(l.getISO6392Code())) {
                return l;
            }
        }
        try {
            return valueOf(lang.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            //throw new IllegalArgumentException(String.format("\"%s\" is not a valid language code.", lang));
            return UNKNOWN;
        }
    }
}
