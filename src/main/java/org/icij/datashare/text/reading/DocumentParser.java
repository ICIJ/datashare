package org.icij.datashare.text.reading;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.logging.Logger;

import org.icij.datashare.text.Language;
import org.icij.datashare.text.reading.tika.TikaDocumentParser;

import static java.util.logging.Level.WARNING;

/**
 * Created by julien on 3/9/16.
 */
public interface DocumentParser {

    enum DocumentParserType {
        TIKA;

        public static Optional<DocumentParserType> parse(final String parser) throws IllegalArgumentException {
            if (parser== null || parser.isEmpty())
                return Optional.empty();
            try {
                return Optional.of(valueOf(parser.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                //throw new IllegalArgumentException(String.format("\"%s\" is not a valid document parser type.", parser));
                return Optional.empty();
            }
        }
    }

    DocumentParserType DEFAULT_PARSER_TYPE = DocumentParserType.TIKA;

    static Optional<DocumentParser> create(DocumentParserType type) {
        if (type == null)
            return Optional.empty();

        switch (type) {
            case TIKA : return Optional.of(new TikaDocumentParser());
            default :
                Logger.getLogger(DocumentParser.class.getName()).log(WARNING, "Unknown DocumentParser type " + type);
                return Optional.empty();
        }
    }

    static Optional<DocumentParser> create() {
        return create(DEFAULT_PARSER_TYPE);
    }


    Optional<String> parse(Path filePath);

    Optional<Language> getLanguage();

    OptionalInt getLength();

    Optional<Charset> getEncoding();

    Optional<String> getName();

    Optional<String> getType();

    Optional<Map<String, String>> getMetadata();

    void disableOcr();

}

