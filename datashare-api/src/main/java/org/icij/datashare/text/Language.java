package org.icij.datashare.text;

import java.io.Serializable;
import java.util.Locale;


/**
 * Languages ISO-6391 and ISO-6392 codes
 *
 * Created by julien on 3/30/16.
 */
public enum Language implements Serializable {
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
    UNKNOWN    ("unknown", "unknown");

    private static final long serialVersionUID =-7964823164978231L;

    private final String iso6391Code;
    private final String iso6392Code;

    Language(final String iso2Code, final String iso1Code) {
        iso6392Code = iso2Code;
        iso6391Code = iso1Code;
    }

    public String iso6391Code() { return iso6391Code; }
    public String iso6392Code() { return iso6392Code; }

    public static Language parse(final String language) {
        if (    language == null ||
                language.isEmpty() ||
                language.equalsIgnoreCase(UNKNOWN.toString())) {
            throw new IllegalArgumentException("no language found for " + language);
        }
        for (Language lang : Language.values()) {
            if (language.equalsIgnoreCase(lang.iso6391Code()) || language.equalsIgnoreCase(lang.iso6392Code())) {
                return lang;
            }
        }
        return valueOf(language.toUpperCase(Locale.ROOT));
    }

}
