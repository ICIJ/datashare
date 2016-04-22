package org.icij.datashare.text.reading;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;
import java.util.OptionalInt;

import org.icij.datashare.text.Language;

/**
 * Created by julien on 3/9/16.
 */
public interface DocumentParser {

    String parse(Path filePath) throws DocumentParserException;

    Optional<Language> getLanguage();

    OptionalInt getLength();

    Optional<Charset> getEncoding();

    Optional<String> getName();

}

