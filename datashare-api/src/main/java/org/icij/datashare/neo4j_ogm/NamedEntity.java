package org.icij.datashare.neo4j_ogm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import me.xuender.unidecode.Unidecode;
import org.icij.datashare.Entity;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.function.ThrowingFunctions;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.IndexId;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.text.nlp.Tag;

import org.neo4j.ogm.annotation.Id;
import org.neo4j.ogm.annotation.GeneratedValue;
import org.neo4j.ogm.annotation.Relationship;
import org.neo4j.ogm.annotation.Property;
import org.neo4j.ogm.annotation.NodeEntity;

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
@NodeEntity
public final class NamedEntity implements Entity {
    @Property private static final long serialVersionUID = 1946532866377498L;

    @Property private String mention;
    @Property private final String mentionNorm;

    @IndexId
    @Id @GeneratedValue
    private final String id;

    @Property private final Category category;

    @IndexParent
    private final String documentId;

    @IndexRoot
    private final String rootDocument;

    @Property private final int offset;
    @Property private final Pipeline.Type extractor;
    @Property private final Language extractorLanguage;
    @Property private final String partsOfSpeech;
    @Property private Boolean hidden;

    @Relationship(type = "FOUND_IN", direction = Relationship.INCOMING)
    private Set<Document> documents;

    public enum Category implements Serializable {
        PERSON       ("PERS"),
        ORGANIZATION ("ORG"),
        LOCATION     ("LOC"),
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
                                     int offset,
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

    public static NamedEntity from(String text, Tag tag, Annotations annotations) {
        Category category = Category.parse(tag.getValue());
        String mention = ThrowingFunctions.removeNewLines.apply(text.substring(tag.getBegin(), tag.getEnd()));
        List<Tag> posTags = annotations.get(POS);
        int posTagIndex = Collections.binarySearch(posTags, tag, Tag.comparator);
        if (posTagIndex > 0) {
            LOGGER.info(posTagIndex + ", " + posTags.get(posTagIndex));
        }
        return NamedEntity.create(
                category,
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
                        @JsonProperty("offset") int offset,
                        @JsonProperty("documentId") String documentId,
                        @JsonProperty("rootDocument") String rootDocument,
                        @JsonProperty("extractor") Pipeline.Type extractor,
                        @JsonProperty("extractorLanguage") Language extractorLanguage,
                        @JsonProperty("isHidden") Boolean hidden,
                        @JsonProperty("partOfSpeech") String partsOfSpeech) {
        if (mention == null || mention.isEmpty()) {
            throw new IllegalArgumentException("Mention is undefined");
        }
//        this.category = Optional.ofNullable(category).orElse(UNKNOWN);
        this.category = category;
        this.mention = mention;
        this.mentionNorm = normalize(mention);
        this.documentId = documentId;
        this.rootDocument = rootDocument;
        this.offset = offset;
        this.extractor = extractor;
        this.id = HASHER.hash( String.join("|",
                getDocumentId().toString(),
                String.valueOf(offset),
                getExtractor().toString(),
                mentionNorm
        ));
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
    public int getOffset() { return offset; }
    public Pipeline.Type getExtractor() { return extractor; }
    public Language getExtractorLanguage() { return extractorLanguage; }
    public Boolean isHidden() { return hidden; }
    public NamedEntity hide() { this.hidden = true; return this;}
    public NamedEntity unhide() { this.hidden = false; return this;}
    public String getPartsOfSpeech() { return partsOfSpeech; }

    public Set<Document> getDocuments() {
        return documents;
    }

    @Override
    public String toString() {
        return "NamedEntity{" +
                "mention='" + mention + '\'' +
                ", id='" + id + '\'' +
                ", category=" + category +
                ", offset=" + offset +
                '}';
    }

    @JsonIgnore
    public static String normalize(String unicoded) {
        return Unidecode.decode(unicoded).trim().replaceAll("(\\s+)", " ").toLowerCase();
    }

}
