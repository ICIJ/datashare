package org.icij.datashare.text.nlp;

import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.reflect.EnumTypeToken;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.icij.datashare.function.ThrowingFunctions.joinComma;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.NER;


/**
 * Extract tokens, sentences, parts-of-speech or named entities from text
 *
 * Created by julien on 4/4/16.
 */
public interface Pipeline {
    Logger LOGGER = LoggerFactory.getLogger(Pipeline.class);

    enum Type implements EnumTypeToken {
        CORENLP,
        GATENLP,
        IXAPIPE,
        MITIE,
        OPENNLP;

        private final String className;

        Type() { className = buildClassName(Pipeline.class, this); }

        @Override
        public String getClassName() { return className; }

        public static Optional<Type> parse(final String valueName) {
            return EnumTypeToken.parse(Type.class, valueName);
        }

        public static Optional<Type> fromClassName(final String className) {
            return EnumTypeToken.parseClassName(Pipeline.class, Type.class, className);
        }

        public static ThrowingFunction<List<String>, List<Pipeline.Type>> parseAll =
                list -> list.stream()
                        .map(Pipeline.Type::parse)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
    }

    enum Property {
        STAGES,
        ENTITIES,
        CACHING,
        LANGUAGE,
        ENCODING;

        public String getName() {
            return name().toLowerCase().replace('_', '-');
        }

        public static Function<List<NlpStage>, Function<List<NamedEntity.Category>, Function<Boolean, Properties>>>
                build =
                nlpStages -> entityCategories -> enableCaching -> {
                    Properties properties = new Properties();
                    properties.setProperty(STAGES.getName(),   joinComma.apply(nlpStages));
                    properties.setProperty(ENTITIES.getName(), joinComma.apply(entityCategories));
                    properties.setProperty(CACHING.getName(),  String.valueOf(enableCaching));
                    return properties;
                };
    }


    Charset DEFAULT_ENCODING = UTF_8;

    Language DEFAULT_LANGUAGE = ENGLISH;

    Type DEFAULT_TYPE = Type.GATENLP;

    int DEFAULT_PARALLELISM = 1;

    List<NlpStage> DEFAULT_TARGET_STAGES = singletonList(NER);

    List<NamedEntity.Category> DEFAULT_ENTITIES = asList(PERSON, ORGANIZATION, LOCATION);

    boolean DEFAULT_CACHING = true;


    /**
     * Instantiate a concrete {@code Pipeline} reflectively, implementation determined by {@link Type} enum value
     *
     * @param type       the {@link Type} enum value denoting a {@code Pipeline} implementation
     * @param properties the {@code Pipeline} settings as Properties
     * @return the corresponding Pipeline implementation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     * */
    static Optional<Pipeline> create(Type type, Properties properties)  {
        String interfaceName = Pipeline.class.getName();
        if ( ! asList(Type.values()).contains(type)) {
            LOGGER.error("Unknown " + type + " " + interfaceName);
            return Optional.empty();
        }
        try {
            Object pipelineInstance = Class.forName( type.getClassName() )
                    .getDeclaredConstructor( new Class[]{Properties.class} )
                    .newInstance           (             properties        );
            return Optional.of( (Pipeline) pipelineInstance );
        } catch (ClassNotFoundException e) {
            LOGGER.error( type.getClassName() + " not found in the classpath.", e);
            return Optional.empty();
        } catch (InvocationTargetException e) {
            LOGGER.error( "Failed to instantiate " + type + " " + interfaceName, e.getCause());
            return Optional.empty();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            LOGGER.error("Failed to instantiate " + type + " " + interfaceName, e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate a concrete {@code Pipeline} of {@link Pipeline#DEFAULT_TYPE}
     *
     * @param properties the {@code Pipeline} settings as Properties
     * @return the corresponding Pipeline implementation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     */
    static Optional<Pipeline> create(Properties properties) {
        return create(DEFAULT_TYPE, properties);
    }

    /**
     * Instantiate a concrete default {@code Pipeline}
     *
     * @param type the {@link Type} enum value denoting a {@code Pipeline} implementation
     * @return the corresponding Pipeline implementation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     */
    static Optional<Pipeline> create(Type type) {
        return create(type, new Properties());
    }

    /**
     * Instantiate a default concrete {@code Pipeline}
     *
     * @return the corresponding Pipeline implementation instance if succeeded; empty Optional otherwise
     */
    static Optional<Pipeline> create() {
        return create(new Properties());
    }


    /**
     * @return the corresponding implementation {@link Type} enum value
     */
    Type getType();


    /**
     * Run pipeline on a {@link  org.icij.datashare.text.Document}
     * Use document's language
     *
     * @param document the document to process
     */
    Optional<Annotation> run(Document document);

    /**
     * Run pipeline on a {@link  org.icij.datashare.text.Document}
     * Force language
     *
     * @param document the document to process
     * @param language the forced processing language
     */
    Optional<Annotation> run(Document document, Language language);

    /**
     * Run pipeline on a {@link Path}
     * Use document's language
     *
     * @param path the file Path to process
     */
    Optional<Annotation> run(Path path);

    /**
     * Run pipeline from a {@link Path}
     * Force language
     *
     * @param path     the file Path to process
     * @param language the forced processing language
     */
    Optional<Annotation> run(Path path, Language language);

    /**
     * Run pipeline on a {@link String}
     * Force default language
     *
     * @param text the input string to process
     * @see #DEFAULT_LANGUAGE
     */
    default Optional<Annotation> run(String text) {
        if (text.isEmpty())
            return Optional.empty();
        return run(text, DEFAULT_LANGUAGE);
    }

    /**
     * Run pipeline on a {@link String} with language
     *
     * @param text     the input string to process
     * @param language the forced processing language
     */
    Optional<Annotation> run(String text, Language language);

    /**
     * Is stage supported for language?
     *
     * @param stage     the stage to test for support
     * @param language  the language on which stage is tested
     * @return true if stage supports language; false otherwise
     */
    boolean supports(NlpStage stage, Language language);

    /**
     * @return the list of all targeted named entity categories
     */
    List<NamedEntity.Category> getTargetEntities();

    /**
     * @return the list of all involved stages
     */
    List<NlpStage> getStages();

    /**
     * @return true if pipeline is caching annotators; false otherwise
     */
    boolean isCaching();

    /**
     * @return the list of all involved stages
     */
    Charset getEncoding();

    /**
     * @return the tagset used by the part-of-speech tagger
     */
    Optional<String> getPosTagSet(Language language);

}
