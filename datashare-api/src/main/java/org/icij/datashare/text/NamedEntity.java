package org.icij.datashare.text;

import java.util.*;
import java.util.stream.Collectors;
import static java.util.Collections.emptyList;
import static java.util.Arrays.fill;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;

import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.NlpPipeline;
import org.icij.datashare.text.nlp.Annotation;
import org.icij.datashare.text.nlp.Tag;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.NlpStage.POS;
import org.icij.datashare.util.function.ThrowingFunction;
import org.icij.datashare.util.function.ThrowingFunctions;
import static org.icij.datashare.util.function.ThrowingFunctions.normal;
import static org.icij.datashare.util.function.ThrowingFunctions.removePattFrom;


/**
 * DataShare Named Entity
 *
 * Created by julien on 5/12/16.
 */
@IndexType("NamedEntity")
public class NamedEntity implements Entity {

    public enum Category {
        PERSON       ("PERS"),
        ORGANIZATION ("ORG"),
        LOCATION     ("LOC"),
        DATE         ("DATE"),
        MONEY        ("MON"),
        NUMBER       ("NUM"),
        NONE         ("NONE"),
        UNKNOWN      ("UNK");

        private final String abbreviation;

        Category(final String abbrev) { abbreviation = abbrev; }

        public String getAbbreviation() { return abbreviation; }

        public static Optional<Category> parse(String entity) {
            if (entity == null || entity.isEmpty())
                return Optional.empty();
            if (entity.trim().equals("0") || entity.trim().equals("O"))
                return Optional.of(NONE);
            try {
                return Optional.of(valueOf(entity.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                for (Category cat : values()) {
                    if ( removePattFrom.apply("^I-").apply(entity).equalsIgnoreCase(cat.getAbbreviation()))
                        return Optional.of(cat);
                }
                return Optional.empty();
            }
        }

        public static ThrowingFunction<List<String>, List<Category>> parseAll =
                list -> list.stream()
                        .map(Category::parse)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(Collectors.toList());
    }


    private static final Logger LOGGER = LogManager.getLogger(NamedEntity.class);

    private static final long serialVersionUID = 1946532866377498L;

    public static long getSerialVersionUID() { return serialVersionUID; }


    /**
     * Instantiate a new {@code NamedEntity} from mere category and mention, without context document
     *
     * @param cat      the named entity category
     * @param mention  the string denoting the named entity
     * @return a new immutable {@link NamedEntity} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<NamedEntity> create(Category cat, String mention)  {
        try {
            return Optional.of( new NamedEntity(mention, cat, null, -1, null, null, null) );
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to create named entity", e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate new {@code NamedEntity}, with context document, without part-of-speech
     *
     * @param cat      the named entity category
     * @param mention  the string denoting the named entity
     * @param offset   the offset in context document
     * @param doc      the context document hash
     * @param extr     the pipeline that extracted mention
     * @param extrLang the pipeline language
     * @return a new immutable {@link NamedEntity} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<NamedEntity> create(Category cat,
                                               String mention,
                                               int offset,
                                               String doc,
                                               NlpPipeline.Type extr,
                                               Language extrLang) {
        try {
            return Optional.of( new NamedEntity(mention, cat, doc, offset, extr, extrLang, null) );
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to createList named entity", e);
            return Optional.empty();
        }
    }

    /**
     * Instantiate new {@code NamedEntity}, with context document, with part-of-speech
     *
     * @param cat      the named entity category
     * @param mention  the string denoting the named entity
     * @param offset   the offset in context document
     * @param doc      the context document hash
     * @param extr     the pipeline that extracted mention
     * @param extrLang the pipeline language
     * @param pos      the part(s)-of-speech associated to mention
     * @return an Optional of immutable {@link NamedEntity} if instantiation succeeded; empty Optional otherwise
     */
    public static Optional<NamedEntity> create(Category cat,
                                               String mention,
                                               int offset,
                                               String doc,
                                               NlpPipeline.Type extr,
                                               Language extrLang,
                                               String pos) {
        try {
            return Optional.of( new NamedEntity(mention, cat, doc, offset, extr, extrLang, pos) );
        } catch (IllegalArgumentException e) {
            LOGGER.error("Failed to createList named entity", e);
            return Optional.empty();
        }
    }

    /**
     * Named entities from {@link Document}, {@link Annotation}
     *
     * @param document   the document holding textual content
     * @param annotation the annotation holding named entities coordinates within document
     * @return the list of corresponding named entities if annotation does refer to document; an empty list otherwise
     */
    public static List<NamedEntity> allFrom(Document document, Annotation annotation) {
        if ( ! annotation.getDocument().equals(document.getHash()))
            return emptyList();
        return annotation.get(NER).stream()
                .map     (tag -> from(document.getContent(), tag, annotation))
                .filter  (Optional::isPresent)
                .map     (Optional::get)
                .collect (Collectors.toList());
    }

    private static Optional<NamedEntity> from(String text, Tag tag, Annotation annotation) {
        Optional<NamedEntity.Category> category = NamedEntity.Category.parse(tag.getValue());
        if ( ! category.isPresent()) {
            return Optional.empty();
        }
        String mention = ThrowingFunctions.removeNewLines.apply(text.substring(tag.getBegin(), tag.getEnd()));
        List<Tag> posTags = annotation.get(POS);
        int posTagIndex = Collections.binarySearch(posTags, tag, Tag.comparator);
        if (posTagIndex > 0) {
            LOGGER.info(posTagIndex + "," + posTags.get(posTagIndex));
        }
        return NamedEntity.create(
                category.get(),
                mention,
                tag.getBegin(),
                annotation.getDocument(),
                annotation.getPipeline(),
                annotation.getLanguage()
        );
    }


    // Actual string denoting the named entity
    private final String mention;

    // Mention's hash
    @IndexId
    @JsonIgnore
    private final String hash;

    // Category (Pers, Org, Loc)
    private final Category category;

    // Document uid (hash) from which mention was extracted
    @IndexParent
    private final String document;

    // Offset in document (lower bound on number of chars from beginning)
    private final int offset;

    // Type of pipeline which has extracted mention
    private final NlpPipeline.Type extractor;

    // Language used by pipeline at extraction time
    private final Language extractorLanguage;

    // Parts-of-speech associated with mention
    private final String partsOfSpeech;


    @JsonCreator
    private NamedEntity(String           mention,
                        Category         category,
                        String           document,
                        int              offset,
                        NlpPipeline.Type extractor,
                        Language         extractorLanguage,
                        String           partsOfSpeech) {
        if (mention == null || mention.isEmpty()) {
            throw new IllegalArgumentException("Mention is undefined");
        }
        this.mention = mention;
        if (category == null) {
            throw new IllegalArgumentException("Category is undefined");
        }
        this.category = category;
        hash = HASHER.hash( mentionNormalForm() );
        this.document = document;
        this.offset = offset;
        this.extractor = extractor;
        this.extractorLanguage = extractorLanguage;
        this.partsOfSpeech = partsOfSpeech;
    }


    @Override
    public String getHash() { return hash; }

    public String getMention() { return mention; }

    public Category getCategory() { return category; }

    public Optional<String> getDocument() { return Optional.ofNullable(document); }

    public OptionalInt getOffset() { return OptionalInt.of(offset); }

    public Optional<NlpPipeline.Type> getExtractor() { return Optional.ofNullable(extractor); }

    public Optional<Language> getExtractorLanguage() { return Optional.ofNullable(extractorLanguage); }

    public Optional<String> getPartsOfSpeech() { return Optional.ofNullable(partsOfSpeech); }


    @Override
    public String toString() {
        String[] features = new String[9];
        fill(features, "NONE");
        features[0] = getMention();
        features[1] = getCategory().toString();
        getOffset()           .ifPresent(off      -> features[2] = String.valueOf(off));
        getExtractor()        .ifPresent(extr     -> features[3] = extr.toString());
        getExtractorLanguage().ifPresent(extrLang -> features[4] = extrLang.toString().toUpperCase(Locale.ROOT));
        getPartsOfSpeech()    .ifPresent(pos      -> features[5] = pos.toUpperCase(Locale.ROOT));
        features[6] = mentionNormalForm();
        features[7] = getHash();
        getDocument().ifPresent(docHash -> features[8] = docHash);
        return String.join(";", Arrays.stream(features)
                .map(f -> '"' + f + '"')
                .collect(Collectors.toList()));
    }

    /**
     *  Mention's normal form (from which hash is computed)
     *
     * @return normalized mention
     */
    @JsonIgnore
    private String mentionNormalForm() {
        return normal.apply(mention);
    }

}
