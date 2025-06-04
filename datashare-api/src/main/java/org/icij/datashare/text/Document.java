package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.DocumentMetadataConstants;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.Supplier;

import static java.nio.file.Paths.get;
import static java.time.OffsetDateTime.now;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.text.StringUtils.isEmpty;


@IndexType("Document")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Document implements Entity, DocumentMetadataConstants {
    private static final long serialVersionUID = 5913568429773112L;
    /**
     * see git log for pom.xml in <a href="https://github.com/ICIJ/extract">extract-lib</a>
     */
    static final Map<Date, String> TIKA_VERSION_RECORDS = new ConcurrentSkipListMap<>() {{
        put(Date.from(Instant.parse("1970-01-01T00:00:00Z")), 	"1.8.0" );
        put(Date.from(Instant.parse("2015-07-08T03:59:48Z")), 	"1.9.0" );
        put(Date.from(Instant.parse("2015-08-10T23:05:19Z")), 	"1.10.0" );
        put(Date.from(Instant.parse("2015-10-28T13:36:23Z")), 	"1.11.0" );
        put(Date.from(Instant.parse("2016-02-27T01:46:47Z")), 	"1.12.0" );
        put(Date.from(Instant.parse("2016-05-24T15:29:32Z")), 	"1.13.0" );
        put(Date.from(Instant.parse("2016-11-04T18:58:15Z")), 	"1.14rc1");
        put(Date.from(Instant.parse("2016-11-14T12:23:43Z")), 	"1.14.0" );
        put(Date.from(Instant.parse("2017-06-11T18:43:49Z")), 	"1.15.0" );
        put(Date.from(Instant.parse("2017-08-17T16:09:55Z")), 	"1.16.0" );
        put(Date.from(Instant.parse("2018-02-13T09:49:53Z")), 	"1.17.0" );
        put(Date.from(Instant.parse("2018-06-11T13:04:21Z")), 	"1.18.0" );
        put(Date.from(Instant.parse("2019-06-07T10:35:53Z")), 	"1.20.0" );
        put(Date.from(Instant.parse("2019-08-12T15:16:26Z")), 	"1.22.0" );
        put(Date.from(Instant.parse("2020-09-14T08:27:25Z")), 	"1.24.1" );
        put(Date.from(Instant.parse("2020-09-16T16:20:26Z")), 	"1.22.0" );
        put(Date.from(Instant.parse("2021-04-02T16:13:51Z")), 	"1.24.1" );
        put(Date.from(Instant.parse("2021-04-02T16:52:36Z")), 	"1.22.0" );
        put(Date.from(Instant.parse("2022-10-10T11:10:34Z")), 	"1.23.0" );
        put(Date.from(Instant.parse("2022-10-20T11:57:11Z")), 	"2.4.1");
        put(Date.from(Instant.parse("2025-03-12T09:58:47Z")), 	"2.9.3");
        put(Date.from(Instant.parse("2025-03-12T10:52:07Z")), 	"3.1.0");
    }};
    public enum Status {
        PARSED((short)0), INDEXED((short)1), DONE((short)2);

        public final short code;
        Status(short code) {
            this.code = code;
        }

        public static Status fromCode(final int code) {
            for (Status status : Status.values()) {
                if (status.code == code) {
                    return status;
                }
            }
            throw new IllegalArgumentException("invalid status code " + code);
        }
    }
    @JsonIgnore
    private final Project project;
    private final String id;
    @JsonSerialize(using = PathSerializer.class)
    @JsonDeserialize(using = PathDeserializer.class)
    private final Path path;
    @JsonSerialize(using = PathSerializer.class)
    @JsonDeserialize(using = PathDeserializer.class)
    private final Path dirname;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private final Date extractionDate;
    private final short extractionLevel;

    private final String content;
    private final List<Map<String,String>> content_translated;
    private final long contentLength;
    private final String contentType;
    @JsonDeserialize(using = CharsetDeserializer.class)
    private final Charset contentEncoding;
    private final Language language;
    private final Map<String, Object> metadata;
    private final Status status;
    private final Set<Pipeline.Type> nerTags;
    private final Set<Tag> tags;

    @IndexParent
    private final String parentDocument;
    @IndexRoot
    private final String rootDocument;


    Document(Project project, String id, Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, Object> metadata, Status status, Set<Pipeline.Type> nerTags, Date extractionDate, String parentDocument, String rootDocument, Short extractionLevel, Long contentLength) {
        this(project, id, filePath, content,null, language, extractionDate, charset, mimetype, extractionLevel, metadata, status, nerTags, parentDocument, rootDocument, contentLength, new HashSet<>());
    }

    Document(Project project, String id, Path filePath, String content, List<Map<String,String>> content_translated, Language language, Charset charset,
                    String contentType, Map<String, Object> metadata, Status status, Set<Pipeline.Type> nerTags,
                    Date extractionDate, String parentDocument, String rootDocument, Short extractionLevel,
                    Long contentLength, Set<Tag> tags) {
        this(project, id, filePath, content, content_translated, language, extractionDate, charset,
                contentType, extractionLevel, metadata, status, nerTags,
                parentDocument, rootDocument, contentLength,
                tags);
    }

    @JsonCreator
    private Document(@JsonProperty("projectId") Project project, @JsonProperty("id") String id, @JsonProperty("path") Path path,
                     @JsonProperty("content") String content,
                     @JsonProperty("content_translated") List<Map<String,String>> content_translated,
                     @JsonProperty("language") Language language, @JsonProperty("extractionDate") Date extractionDate,
                     @JsonProperty("contentEncoding") Charset contentEncoding, @JsonProperty("contentType") String contentType,
                     @JsonProperty("extractionLevel") int extractionLevel,
                     @JsonProperty("metadata") Map<String, Object> metadata,
                     @JsonProperty("status") Status status,
                     @JsonProperty("nerTags") Set<Pipeline.Type> nerTags,
                     @JsonProperty("parentDocument") String parentDocument,
                     @JsonProperty("rootDocument") String rootDocument,
                     @JsonProperty("contentLength") Long contentLength,
                     @JsonProperty("tags") Set<Tag> tags) {
        this.id = id;
        this.project = project;
        this.path = path;
        this.dirname = path == null ? null: getDirnameFrom(path);
        this.content = ofNullable(content).orElse("");
        this.content_translated = ofNullable(content_translated).orElse(new ArrayList<>());
        this.extractionDate = extractionDate;
        this.extractionLevel = (short)extractionLevel;
        this.contentLength = ofNullable(contentLength).orElse(0L);
        this.language = language;
        this.contentType = contentType;
        this.contentEncoding = contentEncoding;
        this.metadata = metadata;
        this.status = status;
        this.nerTags = nerTags;
        this.parentDocument = parentDocument;
        this.rootDocument = rootDocument;
        this.tags = tags;
    }

    static String getHash(Project project, Path path) {
        return DEFAULT_DIGESTER.hash(path, project.getId());
    }

    @Override
    public String getId() { return id; }
    @JsonIgnore
    public Project getProject() { return project;}
    public String getProjectId() { return project.getId();}
    public String getContent() { return content; }
    @JsonGetter("content_translated")
    public List<Map<String, String>> getContentTranslated() { return content_translated; }
    public int getContentTextLength() { return content.length();}
    public Path getPath() { return path;}
    public Path getDirname() { return dirname;}
    public Date getExtractionDate() { return extractionDate;}
    public Charset getContentEncoding() { return contentEncoding; }
    public Long getContentLength() { return contentLength; }
    public String getContentType() { return contentType; }
    public String getContentTypeOrDefault() { return ofNullable(getContentType()).orElse(DEFAULT_VALUE_UNKNOWN); }
    public Language getLanguage() { return language; }
    public short getExtractionLevel() { return extractionLevel;}
    public String getRootDocument() {return ofNullable(rootDocument).orElse(getId());}
    public boolean isRootDocument() {return getRootDocument().equals(getId());}
    public String getParentDocument() { return parentDocument;}
    public Status getStatus() { return status;}
    public Set<Pipeline.Type> getNerTags() { return nerTags;}
    public Set<Tag> getTags() { return tags;}
    public Date getCreationDate() {
        String creationDate = (String) ofNullable(metadata).orElse(new HashMap<>()).get("tika_metadata_dcterms_created");
        if (creationDate == null) return null;
        Instant instant = null;
        try {
            instant = ZonedDateTime.parse(creationDate).toInstant();
        } catch (DateTimeParseException e) {
            LOGGER.debug("exception when parsing creation date (" + e + ") trying with local date time");
        }
        try {
            instant = LocalDateTime.parse(creationDate).toInstant(now().getOffset());
        } catch (DateTimeParseException ignored) {}
        return instant == null ? null:Date.from(instant);
    }

    @JsonIgnore
    public boolean isEmail() {
        final String contentType = getContentTypeOrDefault();
        return "application/vnd.ms-outlook".equals(contentType) || contentType.startsWith("message/");
    }

    @JsonIgnore
    public boolean isJson() {
        return getContentTypeOrDefault().contains("application/json");
    }

    public String getTitle() {
        if (isEmail()) {
            String emailSubject = getMetadataTitle(SUBJECT);
            if (!isEmpty(emailSubject)) {
                return emailSubject;
            }

            String emailTitle = getMetadataTitle(TITLE);
            if (!isEmpty(emailTitle )) {
                return emailTitle;
            }
        }

        if (isJson()) {
            String jsonTitle = getMetadataTitle(TITLE);
            if (!isEmpty(jsonTitle)) {
                return jsonTitle;
            }
        }

        return getMetadataTitle(RESOURCE_NAME_KEY, () -> DEFAULT_VALUE_UNKNOWN);
    }

    public String getTitleNorm() {
        return StringUtils.normalize(getTitle());
    }

    @JsonGetter
    public String getExtractorVersion() {
        return getMetadataTitle(TIKA_VERSION, () -> getTikaVersion(getExtractionDate()));
    }

    @JsonIgnore
    public short getNerMask() { return nerMask(getNerTags());}
    public Map<String, Object> getMetadata() { return metadata; }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount() - 1).toString(); }

    static String getTikaVersion(Date date) {
        Map.Entry<Date, String> previous = TIKA_VERSION_RECORDS.entrySet().iterator().next();
        for (Map.Entry<Date, String> entry: TIKA_VERSION_RECORDS.entrySet()) {
            if (entry.getKey().after(date)) {
                return versionWithPrefix(previous.getValue());
            }
            previous = entry;
        }
        return versionWithPrefix(previous.getValue());
    }

    static String versionWithPrefix(String rawVersion) {
        return "Apache Tika " + rawVersion;
    }

    @Override
    public String toString() {
        return (path != null ? getName() : "") + "(" + this.getId() + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Document document = (Document) o;
        return Objects.equals(id, document.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    private static Path getDirnameFrom(Path filePath) { return ofNullable(filePath.getParent()).orElse(get(""));}
    static short nerMask(Set<Pipeline.Type> typeSet) {
         return typeSet.stream().map(t -> t.mask).reduce(Integer::sum).orElse(0).shortValue();
    }
    public static Set<Pipeline.Type> fromNerMask(int mask) {
        return mask == 0 ? new HashSet<>():
                stream(Pipeline.Type.values()).filter(t -> (mask & t.mask) == t.mask ).collect(toSet());
    }

    private String getMetadataTitle(String key) {
        return getMetadataTitle(key, () -> null);
    }

    /**
     * supplier to extract default value only if needed when it can be CPU intensive.
     *
     * @param key of the metadata
     * @param defaultValue supplier for default value
     * @return value of the key sanitized (non null)
     */
    private String getMetadataTitle(String key, Supplier<String> defaultValue) {
        return ofNullable(metadata.get(getField(key))).orElse(Objects.requireNonNullElse(defaultValue.get(), "")).toString();
    }
}
