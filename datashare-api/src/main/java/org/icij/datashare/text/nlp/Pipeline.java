package org.icij.datashare.text.nlp;

import org.icij.datashare.reflect.EnumTypeToken;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;

import java.nio.charset.Charset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static java.util.Collections.singletonList;
import static org.icij.datashare.function.ThrowingFunctions.joinComma;
import static org.icij.datashare.text.NamedEntity.Category.*;
import static org.icij.datashare.text.nlp.NlpStage.NER;


public interface Pipeline {
    static Set<Type> set(Type ...types) {
        return new HashSet<>(Arrays.asList(types));
    }

    enum Type implements EnumTypeToken {
        CORENLP((short)0),
        GATENLP((short)1),
        IXAPIPE((short)2),
        MITIE((short)3),
        OPENNLP((short)4),
        EMAIL((short)5);

        private final String className;
        public final short code;
        public final int mask;

        Type(final short code) {
            this.code = code;
            mask = 1 << code;
            className = buildClassName(Pipeline.class, this);
        }

        public static Type fromCode(final int code) {
            for (Type t: Type.values()) {
                if (t.code == code) {
                    return t;
                }
            }
            throw new IllegalArgumentException("cannot find code " + code);
        }

        @Override
        public String getClassName() { return className; }

        public static Type parse(final String valueName) {
            return EnumTypeToken.parse(Type.class, valueName).
                    orElseThrow(() -> new IllegalArgumentException("unknown pipeline type: " + valueName));
        }

        public static Optional<Type> fromClassName(final String className) {
            return EnumTypeToken.parseClassName(Pipeline.class, Type.class, className);
        }

        public static Set<Pipeline.Type> parseAll(final String comaSeparatedTypes) {
            return comaSeparatedTypes == null || comaSeparatedTypes.isEmpty() ? new HashSet<>():
                    stream(comaSeparatedTypes.split(",")).map(Type::valueOf).collect(Collectors.toSet());
        }
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
    List<NlpStage> DEFAULT_TARGET_STAGES = singletonList(NER);
    List<NamedEntity.Category> DEFAULT_ENTITIES = asList(PERSON, ORGANIZATION, LOCATION);
    boolean DEFAULT_CACHING = true;

    Type getType();

    boolean initialize(Language language) throws InterruptedException;
    Annotations process(String content, String docId, Language language) throws InterruptedException;
    void terminate(Language language) throws InterruptedException ;

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
