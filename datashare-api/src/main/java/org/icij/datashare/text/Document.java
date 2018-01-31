package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.*;
import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Annotation;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;


@IndexType("Document")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Document implements Entity {
    private static final long serialVersionUID = 5913568429773112L;

    private final String id;
    private final Path path;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private final Date extractionDate;
    private final int extractionLevel;

    private final String content;
    private final int contentLength;
    private final String contentType;
    private final Charset contentEncoding;
    private final Language language;
    private final Map<String, String> metadata;

    @JsonCreator
    private Document(@JsonProperty("id") String id, @JsonProperty("path") Path path, @JsonProperty("content") String  content,
                     @JsonProperty("language") Language language, @JsonProperty("extractionDate") Date extractionDate,
                     @JsonProperty("contentEncoding") Charset contentEncoding, @JsonProperty("contentType") String contentType,
                     @JsonProperty("extractionLevel") int extractionLevel,
                     @JsonProperty("metadata") Map<String, String> metadata) {
        this.id = id;
        this.path = path;
        this.content = content;
        this.extractionDate = extractionDate;
        this.extractionLevel = extractionLevel;
        this.contentLength = content.length();
        this.language = language;
        this.contentType = contentType;
        this.contentEncoding = contentEncoding;
        this.metadata = metadata;
    }

    @Override
    public String getId() { return id; }
    public String getContent() { return content; }
    public Path getPath() { return path;}
    public Date getExtractionDate() { return extractionDate; }
    public Charset getContentEncoding() { return contentEncoding; }
    public Integer getContentLength() { return contentLength; }
    public String getContentType() { return contentType; }
    public Language getLanguage() { return language; }
    public int getExtractionLevel() { return extractionLevel;}
    public Map<String, String> getMetadata() { return metadata; }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount()-1).toString(); }

    public List<NamedEntity> namedEntities(Annotation annotation) {
        return NamedEntity.allFrom(this, annotation);
    }

    @Override
    public String toString() {
        return getName() + "(" + this.getId() + ")";
    }

    public static Optional<Document> create(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata) {
        String hash = HASHER.hash(content);
        return Optional.of(new Document(hash, filePath, content, language, new Date(), charset, mimetype, 0, metadata));
    }
}
