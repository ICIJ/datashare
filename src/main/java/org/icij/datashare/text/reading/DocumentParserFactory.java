package org.icij.datashare.text.reading;

import org.icij.datashare.text.reading.tika.TikaDocumentParser;

import java.util.Optional;
import java.util.logging.Logger;

/**
 * Created by julien on 4/18/16.
 */
public class DocumentParserFactory {

    public static Optional<DocumentParser> create(String name, Logger logger) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        switch (name.trim().toLowerCase()) {
            case "tika" : return Optional.of(new TikaDocumentParser(logger));
            default     : return Optional.empty();
        }
    }
}
