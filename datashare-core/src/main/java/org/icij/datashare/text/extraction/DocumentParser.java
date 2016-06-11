package org.icij.datashare.text.extraction;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import org.icij.datashare.text.Language;

import static java.util.Arrays.asList;
import static java.util.logging.Level.SEVERE;


/**
 * Created by julien on 3/9/16.
 */
public interface DocumentParser {

    DocumentParserType DEFAULT_PARSER_TYPE = DocumentParserType.TIKA;

    List<String> SUPPORTED_FILE_EXTS = asList(
            "txt", "rtf", "doc", "docx", "pdf", "html", "xml", "msg", "eml", "xls", "xlsx", "pptx", "tif"
    );


    enum DocumentParserType {
        TIKA ("tika.TikaDocumentParser");

        private final String fullyQualifiedClassName;

        DocumentParserType(String className) {
            String basePackageName = DocumentParser.class.getPackage().getName().replace("/", ".");
            fullyQualifiedClassName = String.join(".", basePackageName, className);
        }

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

        public String getFullyQualifiedClassName() {
            return fullyQualifiedClassName;
        }

    }


    static Optional<DocumentParser> create(DocumentParserType type) {
        if (type == null)
            return Optional.empty();

        switch (type) {
            case TIKA :
                try {
                    String className = type.getFullyQualifiedClassName();
                    Class<?> documentParserClass = Class.forName(className);
                    Object nlpPipelineInstance = documentParserClass.getDeclaredConstructor().newInstance();
                    return Optional.of((DocumentParser) nlpPipelineInstance);
                } catch (ClassNotFoundException e) {
                    Logger.getLogger(DocumentParser.class.getName()).log(SEVERE, "DocumentParser " + type + " not installed." , e);
                    return Optional.empty();
                } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                    Logger.getLogger(DocumentParser.class.getName()).log(SEVERE, "Failed to instantiate DocumentParser " + type, e);
                    return Optional.empty();
                }
            default :
                Logger.getLogger(DocumentParser.class.getName()).log(SEVERE, "Unknown DocumentParser " + type);
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

