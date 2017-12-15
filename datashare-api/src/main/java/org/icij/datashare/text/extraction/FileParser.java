package org.icij.datashare.text.extraction;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.icij.datashare.reflect.EnumTypeToken;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.extraction.FileParser.Type.TIKA;


/**
 * Extract text and metadata from files
 *
 * Created by julien on 3/9/16.
 */
public interface FileParser extends Serializable {
    Log LOGGER = LogFactory.getLog(FileParser.class);

    enum Type implements EnumTypeToken, Serializable {
        TIKA;

        private static final long serialVersionUID = -3791536248652568L;

        private final String className;

        Type() { className = buildClassName(FileParser.class, this); }

        @Override
        public String getClassName() { return className; }

        public static Optional<Type> parse(final String valueName) {
            return EnumTypeToken.parse(Type.class, valueName);
        }

        public static Optional<Type> fromClassName(final String className) {
            return EnumTypeToken.parseClassName(FileParser.class, Type.class, className);
        }
    }

    enum Property {
        ENABLE_OCR,
        OCR_LANGUAGE;

        public String getName() {
            return name().toLowerCase().replace('_', '-');
        }

        public static Function<Boolean, Function<Language, Properties>>
                build = enableOcr -> ocrLanguage -> {
            Properties properties = new Properties();
            properties.setProperty(ENABLE_OCR.getName(),   String.valueOf(enableOcr));
            properties.setProperty(OCR_LANGUAGE.getName(), String.valueOf(ocrLanguage.toString()));
            return properties;
        };

    }


    Type DEFAULT_TYPE = TIKA;

    int DEFAULT_PARALLELISM = 1;

    boolean DEFAULT_ENABLE_OCR = false;


    /**
     * Instantiate a concrete {@code FileParser} reflectively with a {@link Type} enum value
     *
     * @param type       the {@link Type} enum value denoting a {@code FileParser} implementation
     * @param properties the {@code FileParser} settings as Properties
     * @return the corresponding FileParser implementation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     */
    static Optional<FileParser> create(Type type, Properties properties) {
        String interfaceName = FileParser.class.getName();

        if ( ! asList(Type.values()).contains(type)) {
            LOGGER.error("Unknown type " + interfaceName + " " + type );
            return Optional.empty();
        }

        try {
            Object fileParserInstance = Class.forName(type.getClassName())
                    .getDeclaredConstructor( new Class[]{Properties.class} )
                    .newInstance           (             properties      );
            return Optional.of( (FileParser) fileParserInstance );
        } catch (ClassNotFoundException e) {
            LOGGER.error(type + " " + interfaceName + " not found. Consider installing it.", e);
            return Optional.empty();
        } catch (InvocationTargetException e) {
            LOGGER.error("Failed to instantiate " + type  + " " + interfaceName, e.getCause());
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.error("Failed to instantiate " + type  + " " + interfaceName, e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate a concrete {@code FileParser} of {@link FileParser#DEFAULT_TYPE}
     *
     * @return the corresponding FileParser implementation instance if succeeded; empty Optional otherwise
     * @see EnumTypeToken
     */
    static Optional<FileParser> create(Properties properties) {
        return create(DEFAULT_TYPE, properties);
    }

    /**
     * Instantiate a concrete {@code FileParser} with OCR disabled
     *
     * @return the corresponding FileParser implementation instance if succeeded; empty Optional otherwise
     */
    static Optional<FileParser> create() {
        return create(new Properties());
    }


    /**
     * @return the corresponding implementation {@link Type} enum value
     */
    Type getType();


    /**
     * Parses the file located at filePath
     *
     * @return an optional of Document if succeeded; an empty optional otherwise
     */
    Optional<Document> parse(Path filePath);

}
