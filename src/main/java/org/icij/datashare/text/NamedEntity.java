package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.*;
import java.util.stream.Stream;
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
import static java.util.Arrays.asList;
import static org.icij.datashare.function.ThrowingFunctions.removePattFrom;
import static org.icij.datashare.text.NamedEntity.Category.UNKNOWN;
import static org.icij.datashare.text.nlp.NlpStage.NER;
import static org.icij.datashare.text.nlp.NlpStage.POS;


@IndexType("NamedEntity")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class NamedEntity implements Entity {
    private static final long serialVersionUID = 1946532866377498L;

    private final String mention;
    private final String mentionNorm;

    @IndexId
    @JsonIgnore
    private final String id;
    private final Category category;
    @IndexParent
    @JsonIgnore
    private final String documentId;
    @IndexRoot
    @JsonIgnore
    private final String rootDocument;
    private final List<Long> offsets = new LinkedList<>();
    private final Pipeline.Type extractor;
    private final Language extractorLanguage;
    private final String partsOfSpeech;

    // TODO: we could generics here, but that would break the API
    private final Map<String, Object> metadata;

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
                                     List<Long> offsets,
                                     String doc,
                                     String rootDocument,
                                     Pipeline.Type extr,
                                     Language extrLang) {
        return new NamedEntity(cat, mention, offsets, doc, rootDocument, extr, extrLang, false, null, null);
    }

    public static NamedEntity create(Category cat,
                                     String mention,
                                     List<Long> offsets,
                                     String doc,
                                     String rootDocument,
                                     Pipeline.Type extr,
                                     Language extrLang,
                                     Map<String, Object> metadata
                                     ) {
        return new NamedEntity(cat, mention, offsets, doc, rootDocument, extr, extrLang, false, null, metadata);
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
        return NamedEntity.create(tag.getCategory(), mention, asList((long)tag.getBegin()),
                annotations.documentId, annotations.rootId, annotations.pipelineType, annotations.language
        );
    }

    @JsonCreator
    private NamedEntity(
                        @JsonProperty("category") Category category,
                        @JsonProperty("mention") String mention,
                        @JsonProperty("offsets") List<Long> offsets,
                        @JsonProperty("documentId") String documentId,
                        @JsonProperty("rootDocument") String rootDocument,
                        @JsonProperty("extractor") Pipeline.Type extractor,
                        @JsonProperty("extractorLanguage") Language extractorLanguage,
                        @JsonProperty("isHidden") Boolean hidden,
                        @JsonProperty("partOfSpeech") String partsOfSpeech,
                        @JsonProperty("metadata") Map<String, Object> metadata
    ) {
        if (mention == null || mention.isEmpty()) {
            throw new IllegalArgumentException("Mention is undefined");
        }
        this.mentionNorm = normalize(mention);
        ArrayList<String> hashed = Stream.of(
            documentId,
            String.valueOf(offsets),
            extractor.toString(),
            mentionNorm
        ).collect(Collectors.toCollection(ArrayList::new));
        this.metadata = metadata;
        if (this.metadata != null) {
            hashed.addAll(this.metadata.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> Stream.of(e.getKey(), String.valueOf(e.getValue().hashCode())))
                .collect(Collectors.toList()));
        }
        this.id = HASHER.hash(String.join("|", hashed));
        this.category = Optional.ofNullable(category).orElse(UNKNOWN);
        this.mention = mention;
        this.documentId = documentId;
        this.rootDocument = rootDocument;
        this.offsets.addAll(offsets);
        this.extractor = extractor;
        this.extractorLanguage = extractorLanguage;
        this.hidden = hidden;
        this.partsOfSpeech = partsOfSpeech;

    }

    @Override
    @JsonIgnore
    public String getId() { return id; }
    public String getMention() { return mention; }
    public Category getCategory() { return category; }
    @JsonIgnore
    public String getDocumentId() { return documentId; }
    @JsonIgnore
    public String getRootDocument() { return rootDocument; }
    public int getMentionNormTextLength() {return mentionNorm.length();}
    public List<Long> getOffsets() { return offsets; }
    public Pipeline.Type getExtractor() { return extractor; }
    public Language getExtractorLanguage() { return extractorLanguage; }
    public Map<String, Object> getMetadata() { return metadata; }
    @JsonGetter("isHidden")
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
                ", offsets=" + offsets +
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
