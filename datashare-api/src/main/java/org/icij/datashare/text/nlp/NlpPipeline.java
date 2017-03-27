package org.icij.datashare.text.nlp;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static java.util.Collections.singletonList;
import static java.util.Arrays.asList;
import java.nio.charset.Charset;
import java.nio.file.Path;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.lang.reflect.InvocationTargetException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.icij.datashare.reflect.EnumTypeToken;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import org.icij.datashare.text.NamedEntity;
import static org.icij.datashare.text.NamedEntity.Category.LOCATION;
import static org.icij.datashare.text.NamedEntity.Category.ORGANIZATION;
import static org.icij.datashare.text.NamedEntity.Category.PERSON;
import static org.icij.datashare.function.ThrowingFunctions.joinComma;


/**
 * Extract tokens, sentences, parts-of-speech or named entities from text
 *
 * Created by julien on 4/4/16.
 */
public interface NlpPipeline {

    enum Type implements EnumTypeToken {
        CORE,
        GATE,
        IXA,
        MITIE,
        OPEN;

        private final String className;

        Type() { className = buildClassName(NlpPipeline.class, this); }

        @Override
        public String getClassName() { return className; }

        public static Optional<Type> parse(final String valueName) {
            return EnumTypeToken.parse(Type.class, valueName);
        }

        public static Optional<Type> fromClassName(final String className) {
            return EnumTypeToken.parseClassName(NlpPipeline.class, Type.class, className);
        }

        public static ThrowingFunction<List<String>, List<NlpPipeline.Type>> parseAll =
                list -> list.stream()
                        .map(NlpPipeline.Type::parse)
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

    Type DEFAULT_TYPE = Type.GATE;

    List<NlpStage> DEFAULT_TARGET_STAGES = singletonList(NER);

    List<NamedEntity.Category> DEFAULT_ENTITIES = asList(PERSON, ORGANIZATION, LOCATION);

    boolean DEFAULT_CACHING = true;


    /**
     * Instantiate a concrete {@code NlpPipeline} reflectively, implementation determined by {@link Type} enum value
     *
     * @param type       the {@link Type} enum value denoting a {@code NlpPipeline} implementation
     * @param properties the {@code NlpPipeline} settings as Properties
     * @return the corresponding NlpPipeline implementation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     * */
    static Optional<NlpPipeline> create(Type type, Properties properties)  {
        String interfaceName = NlpPipeline.class.getName();
        Logger logger = LogManager.getLogger(NlpPipeline.class);
        if ( ! asList(Type.values()).contains(type)) {
            logger.error("Unknown " + type + " " + interfaceName);
            return Optional.empty();
        }
        try {
            Object pipelineInstance = Class.forName( type.getClassName() )
                    .getDeclaredConstructor( new Class[]{Properties.class} )
                    .newInstance           (             properties        );
            return Optional.of( (NlpPipeline) pipelineInstance );
        } catch (ClassNotFoundException e) {
            logger.error( type.getClassName() + " not found in the classpath.", e);
            return Optional.empty();
        } catch (InvocationTargetException e) {
            logger.error( "Failed to instantiate " + type + " " + interfaceName, e.getCause());
            return Optional.empty();
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            logger.error("Failed to instantiate " + type + " " + interfaceName, e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate a concrete {@code NlpPipeline} of {@link NlpPipeline#DEFAULT_TYPE}
     *
     * @param properties the {@code NlpPipeline} settings as Properties
     * @return the corresponding NlpPipeline implementation instance if succeeded; empty Optional otherwise
     * @see Type
     * @see EnumTypeToken
     * @see Property
     */
    static Optional<NlpPipeline> create(Properties properties) {
        return create(DEFAULT_TYPE, properties);
    }

    /**
     * Instantiate a default concrete {@code NlpPipeline}
     *
     * @return the corresponding NlpPipeline implementation instance if succeeded; empty Optional otherwise
     */
    static Optional<NlpPipeline> create() {
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
    Optional<Annotation>  run(Document document);

    /**
     * Run pipeline on a {@link  org.icij.datashare.text.Document}
     * Force language
     *
     * @param document the document to process
     * @param language the forced processing language
     */
    Optional<Annotation>  run(Document document, Language language);

    /**
     * Run pipeline on a {@link Path}
     * Use document's language
     *
     * @param path the file Path to process
     */
    Optional<Annotation>  run(Path path);

    /**
     * Run pipeline from a {@link Path}
     * Force language
     *
     * @param path     the file Path to process
     * @param language the forced processing language
     */
    Optional<Annotation>  run(Path path, Language language);

    /**
     * Run pipeline on a {@link String}
     * Force default language
     *
     * @param text the input string to process
     * @see #DEFAULT_LANGUAGE
     */
    default Optional<Annotation>  run(String text) {
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
    Optional<Annotation>  run(String text, Language language);

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
