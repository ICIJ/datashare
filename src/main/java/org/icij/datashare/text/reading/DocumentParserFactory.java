package org.icij.datashare.text.reading;

import org.icij.datashare.text.reading.tika.TikaDocumentParser;

import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;

/**
 * Created by julien on 4/18/16.
 */
public class DocumentParserFactory {

    public enum DocumentParserType {
        TIKA,
        NONE;

        public static DocumentParserType parse(final String parser) throws IllegalArgumentException {
            if (parser== null || parser.isEmpty())
                return NONE;
            try {
                return valueOf(parser.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(String.format("\"%s\" is not a valid document parser type.", parser));
            }
        }
    }

    public static Optional<DocumentParser> build(DocumentParserType type, Logger logger) {
        if (type == null)
            return Optional.empty();

        switch (type) {
            case TIKA : return Optional.of(new TikaDocumentParser(logger));
            default :
                logger.log(WARNING, "Unknown DocumentParser type " + type);
                return Optional.empty();
        }
    }

    public static Optional<DocumentParser> build(Logger logger) {
        return build(DocumentParserType.TIKA, logger);
    }
}
