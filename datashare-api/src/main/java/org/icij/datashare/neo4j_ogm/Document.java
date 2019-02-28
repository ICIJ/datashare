package org.icij.datashare.neo4j_ogm;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.icij.datashare.Entity;
import org.icij.datashare.text.CharsetDeserializer;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.IndexParent;
import org.icij.datashare.text.indexing.IndexRoot;
import org.icij.datashare.text.indexing.IndexType;
import org.icij.datashare.text.nlp.Pipeline;
//import org.icij.datashare.text.Document;
//import org.icij.datashare.neo4j_ogm.NamedEntity;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static java.nio.file.Paths.get;
import static java.util.Optional.ofNullable;


import org.neo4j.ogm.annotation.NodeEntity;
import org.neo4j.ogm.annotation.Relationship;


@IndexType("Document")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonIdentityInfo(generator = JSOGGenerator.class)
@NodeEntity
public class Document implements Entity {
    private static final long serialVersionUID = 5913568429773112L;

    public enum Status {PARSED, INDEXED, DONE}

    private final String id;
    private final Path path;
    private final Path dirname;
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
//    private final Set<Pipeline.Type> nerTags;
//    private final Set<NamedEntity> nerTags;
    @Relationship(type = "HAS_TAGS")
    private Set<NamedEntity> nerTags;

    @IndexParent
    private final String parentDocument;
    @IndexRoot
    private final String rootDocument;

    public Document(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata, Status status) {
        this(HASHER.hash(content), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, new HashSet<>(), null, null);
    }

//    public Document(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata, Status status, HashSet<Pipeline.Type> nerTags) {
//        this(HASHER.hash(content), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, nerTags, null, null);
    public Document(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata, Status status, HashSet<NamedEntity> nerTags) {
        this(HASHER.hash(content), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, nerTags, null, null);
    }

//    public Document(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata, Status status, HashSet<Pipeline.Type> nerTags, Document parentDocument) {
//        this(HASHER.hash(content), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, nerTags, parentDocument.getId(), parentDocument.getRootDocument());
    public Document(Path filePath, String content, Language language, Charset charset, String mimetype, Map<String, String> metadata, Status status, HashSet<NamedEntity> nerTags, Document parentDocument) {
        this(HASHER.hash(content), filePath, getDirnameFrom(filePath), content, language, new Date(), charset, mimetype, 0, metadata, status, nerTags, parentDocument.getId(), parentDocument.getRootDocument());
    }

    @JsonCreator
    private Document(@JsonProperty("id") String id, @JsonProperty("path") Path path,
                     @JsonProperty("dirname") Path dirname, @JsonProperty("content") String  content,
                     @JsonProperty("language") Language language, @JsonProperty("extractionDate") Date extractionDate,
                     @JsonProperty("contentEncoding") Charset contentEncoding, @JsonProperty("contentType") String contentType,
                     @JsonProperty("extractionLevel") int extractionLevel,
                     @JsonProperty("metadata") Map<String, String> metadata,
                     @JsonProperty("status") Status status,
//                     @JsonProperty("nerTags") Set<Pipeline.Type> nerTags,
                     @JsonProperty("nerTags") Set<NamedEntity> nerTags,
                     @JsonProperty("parentDocument") String parentDocument,
                     @JsonProperty("rootDocument") String rootDocument) {
        this.id = id;
        this.path = path;
        this.dirname = dirname;
        this.content = ofNullable(content).orElse("");
        this.extractionDate = extractionDate;
        this.extractionLevel = extractionLevel;
        this.contentLength = this.content.length();
        this.language = language;
        this.contentType = contentType;
        this.contentEncoding = contentEncoding;
        this.metadata = metadata;
        this.status = status;
//        this.nerTags = nerTags;
//        this.nerTags = new HashSet<>();
        this.parentDocument = parentDocument;
        this.rootDocument = rootDocument;
    }

    @Override
    public String getId() { return id; }
    public String getContent() { return content; }
    public Path getPath() { return path;}
    public Path getDirname() { return dirname;}
    public Charset getContentEncoding() { return contentEncoding; }
    public Integer getContentLength() { return contentLength; }
    public String getContentType() { return contentType; }
    public Language getLanguage() { return language; }
    public int getExtractionLevel() { return extractionLevel;}
    public String getRootDocument() {return ofNullable(rootDocument).orElse(getId());}
    public String getParentDocument() { return parentDocument;}
    public Status getStatus() { return status;}

//    public Set<Pipeline.Type> getNerTags() { return nerTags;}
    public Set<NamedEntity> getNerTags() { return nerTags;}
    public Map<String, String> getMetadata() { return metadata; }

    @JsonIgnore
    public String getName() { return path.getName(path.getNameCount()-1).toString(); }
    @Override
    public String toString() {
        return (path != null ? getName(): "") + "(" + this.getId() + ")";
    }

    private static Path getDirnameFrom(Path filePath) { return ofNullable(filePath.getParent()).orElse(get(""));}

}
