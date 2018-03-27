package org.icij.datashare.text;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.icij.datashare.Entity;
import org.icij.datashare.text.indexing.IndexType;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Date;
import java.util.Map;


@IndexType("Document")
@JsonIgnoreProperties(ignoreUnknown = true)
public final class Document implements Entity {
    private static final long serialVersionUID = 5913568429773112L;

    public Status getStatus() {
        return status;
    }

    public enum Status {PARSED, INDEXED, DONE}
    private final String id;
    private final Path path;
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private final Date extractionDate;
    private final int extractionLevel;

    private final String content;
    private final int contentLength;
    private final String contentType;
    @JsonDeserialize(using = CharsetDeserializer.class)
    private final Charset contentEncoding;
    private final Language language;
    private final Map<String, String> metadata;
    private final Status status;

    public Document(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata, Status status) {
        this(HASHER.hash(content), filePath, content, language, new Date(), charset, mimetype, 0, metadata, status);
    }

    @JsonCreator
    private Document(@JsonProperty("id") String id, @JsonProperty("path") Path path, @JsonProperty("content") String  content,
                     @JsonProperty("language") Language language, @JsonProperty("extractionDate") Date extractionDate,
                     @JsonProperty("contentEncoding") Charset contentEncoding, @JsonProperty("contentType") String contentType,
                     @JsonProperty("extractionLevel") int extractionLevel,
                     @JsonProperty("metadata") Map<String, String> metadata, @JsonProperty("status") Status status) {
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
        this.status = status;
    }
    @Override
    public String getId() { return id; }
    public String getContent() { return content; }
    public Path getPath() { return path;}
    public Charset getContentEncoding() { return contentEncoding; }
    public Integer getContentLength() { return contentLength; }
    public String getContentType() { return contentType; }
    public Language getLanguage() { return language; }
    public int getExtractionLevel() { return extractionLevel;}

    public Map<String, String> getMetadata() { return metadata; }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount()-1).toString(); }

    @Override
    public String toString() {
        return getName() + "(" + this.getId() + ")";
    }
}
