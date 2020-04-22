package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import me.xuender.unidecode.Unidecode;
import org.icij.datashare.Entity;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.function.ThrowingFunctions;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpTag;
import org.icij.datashare.text.nlp.Pipeline;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.Math.min;
import static org.icij.datashare.function.ThrowingFunctions.removePattFrom;
import static org.icij.datashare.text.NamedEntity.Category.UNKNOWN;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.NlpStage.POS;


@IndexType("NamedEntity")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class NamedEntity implements Entity {
    private static final long serialVersionUID = 1946532866377498L;

    private String mention;
    private final String mentionNorm;

    @IndexId
    private final String id;
    private final Category category;
    @IndexParent
    private final String documentId;
    @IndexRoot
    private final String rootDocument;
    private final long offset;
    private final Pipeline.Type extractor;
    private final Language extractorLanguage;
    private final String partsOfSpeech;
    private Boolean hidden;

    public enum Category implements Serializable {
        PERSON       ("PERS"),
        ORGANIZATION ("ORG"),
        LOCATION     ("LOC"),
        EMAIL        ("MAIL"),
        DATE         ("DATE"),
        MONEY        ("MON"),
        NUMBER       ("NUM"),
        NONE         ("NONE"),
        UNKNOWN      ("UNK");

        private static final long serialVersionUID = -1596432856473673L;

        private final String abbreviation;

        Category(final String abbrev) { abbreviation = abbrev; }

        public String getAbbreviation() { return abbreviation; }

        public static Category parse(String entityCategory) {
            if (entityCategory == null || entityCategory.isEmpty() ||
                entityCategory.trim().equals("0") || entityCategory.trim().equals("O")) {
                return NONE;
            }
            try {
                return valueOf(entityCategory.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                String normEntityCategory = removePattFrom.apply("^I-").apply(entityCategory);
                for (Category cat : values()) {
                    String catAbbreviation = cat.getAbbreviation();
                    if (normEntityCategory.equalsIgnoreCase(catAbbreviation) ||
                        normEntityCategory.equalsIgnoreCase(catAbbreviation.substring(0, min(catAbbreviation.length(), 3))) )
                        return cat;
                }
                return UNKNOWN;
            }
        }

        public static ThrowingFunction<List<String>, List<Category>> parseAll =
                list -> list.stream()
                        .map(Category::parse)
                        .filter((Category cat) -> cat != UNKNOWN)
                        .collect(Collectors.toList());
    }

    public static NamedEntity create(Category cat,
                                     String mention,
                                     long offset,
                                     String doc,
                                     Pipeline.Type extr,
                                     Language extrLang) {
        return new NamedEntity(cat, mention, offset, doc, doc, extr, extrLang, false, null);
    }

    public static NamedEntity create(Category cat,
                                     String mention,
                                     int offset,
                                     String doc,
                                     String rootDoc,
                                     Pipeline.Type extr,
                                     Language extrLang) {
        return new NamedEntity(cat, mention, offset, doc, rootDoc, extr, extrLang, false, null);
    }

    public static List<NamedEntity> allFrom(String text, Annotations annotations) {
        return annotations.get(NER).stream()
                .map     ( tag -> from(text, tag, annotations) )
                .filter  ( ne -> ne.category != UNKNOWN)
                .collect ( Collectors.toList() );
    }

    public static NamedEntity from(String text, NlpTag tag, Annotations annotations) {
        String mention = ThrowingFunctions.removeNewLines.apply(text.substring(tag.getBegin(), tag.getEnd()));
        List<NlpTag> posTags = annotations.get(POS);
        int posTagIndex = Collections.binarySearch(posTags, tag, NlpTag.comparator);
        if (posTagIndex > 0) {
            LOGGER.info(posTagIndex + ", " + posTags.get(posTagIndex));
        }
        return NamedEntity.create(
                tag.getCategory(),
                mention,
                tag.getBegin(),
                annotations.getDocumentId(),
                annotations.getPipelineType(),
                annotations.getLanguage()
        );
    }

    @JsonCreator
    private NamedEntity(
                        @JsonProperty("category") Category category,
                        @JsonProperty("mention") String mention,
                        @JsonProperty("offset") long offset,
                        @JsonProperty("documentId") String documentId,
                        @JsonProperty("rootDocument") String rootDocument,
                        @JsonProperty("extractor") Pipeline.Type extractor,
                        @JsonProperty("extractorLanguage") Language extractorLanguage,
                        @JsonProperty("isHidden") Boolean hidden,
                        @JsonProperty("partOfSpeech") String partsOfSpeech) {
        if (mention == null || mention.isEmpty()) {
            throw new IllegalArgumentException("Mention is undefined");
        }
        this.mentionNorm = normalize(mention);
        this.id = HASHER.hash( String.join("|",
                documentId,
                String.valueOf(offset),
                extractor.toString(),
                mentionNorm
        ));
        this.category = Optional.ofNullable(category).orElse(UNKNOWN);
        this.mention = mention;
        this.documentId = documentId;
        this.rootDocument = rootDocument;
        this.offset = offset;
        this.extractor = extractor;
        this.extractorLanguage = extractorLanguage;
        this.hidden = hidden;
        this.partsOfSpeech = partsOfSpeech;
    }

    @Override
    public String getId() { return id; }
    public String getMention() { return mention; }
    public Category getCategory() { return category; }
    public String getDocumentId() { return documentId; }
    public String getRootDocument() { return rootDocument; }
    public long getOffset() { return offset; }
    public Pipeline.Type getExtractor() { return extractor; }
    public Language getExtractorLanguage() { return extractorLanguage; }
    public Boolean isHidden() { return hidden; }
    public NamedEntity hide() { this.hidden = true; return this;}
    public NamedEntity unhide() { this.hidden = false; return this;}
    public String getPartsOfSpeech() { return partsOfSpeech; }

    @Override
    public String toString() {
        return "NamedEntity{" +
                "mention='" + mention + '\'' +
                ", id='" + id + '\'' +
                ", category=" + category +
                ", offset=" + offset +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NamedEntity that = (NamedEntity) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @JsonIgnore
    public static String normalize(String unicoded) {
        return Unidecode.decode(unicoded).trim().replaceAll("(\\s+)", " ").toLowerCase();
    }

}
