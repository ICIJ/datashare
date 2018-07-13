package org.icij.datashare.text.indexing;

import org.icij.datashare.text.Language;

public interface LanguageGuesser {
    Language guess(String text);
}
