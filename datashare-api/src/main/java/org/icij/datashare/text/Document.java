package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Pipeline;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

import static java.nio.file.Paths.get;
import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toSet;


@IndexType("Document")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Document implements Entity {
    private static final long serialVersionUID = 5913568429773112L;
    public enum Status {
        PARSED(0), INDEXED(1), DONE(2);

        public final int code;
        Status(int code) {
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
    private final Path path;
    private final Path dirname;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private final Date extractionDate;
    private final int extractionLevel;

    private final String content;
    private final long contentLength;
    private final String contentType;
    @JsonDeserialize(using = CharsetDeserializer.class)
    private final Charset contentEncoding;
    private final Language language;
    private final Map<String, Object> metadata;
    private final Status status;
    private final Set<Pipeline.Type> nerTags;
    @IndexParent
    private final String parentDocument;
    @IndexRoot
    private final String rootDocument;

    public Document(Project project, Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, Object> metadata, Status status, Long contentLength) {
        this(project, getHash(project, filePath), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, new HashSet<>(), null, null, contentLength);
    }

    public Document(Project project, String id, Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, Object> metadata, Status status, Set<Pipeline.Type> nerTags, Date extractionDate, String parentDocument, String rootDocument, Integer extractionLevel, Long contentLength) {
        this(project, id, filePath, getDirnameFrom(filePath), content, language, extractionDate, charset, mimetype, extractionLevel, metadata, status, nerTags, parentDocument, rootDocument, contentLength);
    }

    public Document(Project project, Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, Object> metadata, Status status, Set<Pipeline.Type> nerTags, Long contentLength) {
        this(project, getHash(project, filePath), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, nerTags, null, null, contentLength);
    }

    public Document(Project project, Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, Object> metadata, Status status, HashSet<Pipeline.Type> nerTags, Document parentDocument, Long contentLength) {
        this(project, getHash(project, filePath), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, nerTags, parentDocument.getId(), parentDocument.getRootDocument(), contentLength);
    }

    @JsonCreator
    private Document(@JsonProperty("projectId") Project project, @JsonProperty("id") String id, @JsonProperty("path") Path path,
                     @JsonProperty("dirname") Path dirname, @JsonProperty("content") String content,
                     @JsonProperty("language") Language language, @JsonProperty("extractionDate") Date extractionDate,
                     @JsonProperty("contentEncoding") Charset contentEncoding, @JsonProperty("contentType") String contentType,
                     @JsonProperty("extractionLevel") int extractionLevel,
                     @JsonProperty("metadata") Map<String, Object> metadata,
                     @JsonProperty("status") Status status,
                     @JsonProperty("nerTags") Set<Pipeline.Type> nerTags,
                     @JsonProperty("parentDocument") String parentDocument,
                     @JsonProperty("rootDocument") String rootDocument,
                     @JsonProperty("contentLength") Long contentLength) {
        this.id = id;
        this.project = project;
        this.path = path;
        this.dirname = dirname;
        this.content = ofNullable(content).orElse("");
        this.extractionDate = extractionDate;
        this.extractionLevel = extractionLevel;
        this.contentLength = ofNullable(contentLength).orElse(0L);
        this.language = language;
        this.contentType = contentType;
        this.contentEncoding = contentEncoding;
        this.metadata = metadata;
        this.status = status;
        this.nerTags = nerTags;
        this.parentDocument = parentDocument;
        this.rootDocument = rootDocument;
    }

    private static String getHash(Project project, Path path) {
        return HASHER.hash(path, project.getId());
    }

    @Override
    public String getId() { return id; }
    @JsonIgnore
    public Project getProject() { return project;}
    public String getProjectId() { return project.getId();}
    public String getContent() { return content; }
    public Path getPath() { return path;}
    public Path getDirname() { return dirname;}
    public Date getExtractionDate() { return extractionDate;}
    public Charset getContentEncoding() { return contentEncoding; }
    public Long getContentLength() { return contentLength; }
    public String getContentType() { return contentType; }
    public Language getLanguage() { return language; }
    public int getExtractionLevel() { return extractionLevel;}
    public String getRootDocument() {return ofNullable(rootDocument).orElse(getId());}
    public boolean isRootDocument() {return getRootDocument().equals(getId());}
    public String getParentDocument() { return parentDocument;}
    public Status getStatus() { return status;}
    public Set<Pipeline.Type> getNerTags() { return nerTags;}
    @JsonIgnore
    public int getNerMask() { return nerMask(getNerTags());}
    public Map<String, Object> getMetadata() { return metadata; }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount() - 1).toString(); }

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
    static int nerMask(Set<Pipeline.Type> typeSet) {
         return typeSet.stream().map(t -> t.mask).reduce(Integer::sum).orElse(0);
    }
    public static Set<Pipeline.Type> fromNerMask(int mask) {
        return mask == 0 ? new HashSet<>():
                stream(Pipeline.Type.values()).filter(t -> (mask & t.mask) == t.mask ).collect(toSet());
    }
}
