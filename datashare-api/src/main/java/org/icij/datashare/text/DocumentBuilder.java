package org.icij.datashare.text;

import org.icij.datashare.text.nlp.Pipeline;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

import static java.nio.file.Paths.get;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Project.project;

public class DocumentBuilder {
    private String id;
    private String content;
    private List<Map<String, String>> content_translated;
    private Path path;
    private Map<String, Object> metadata = new HashMap<>();
    private String contentType;
    private Set<Pipeline.Type> pipelines;
    private String parentId = null;
    private String rootId = null;
    private Set<Tag> tags;
    private Project project;
    private Language language = ENGLISH;
    private short extractionLevel;
    private Charset charset;
    private Document.Status documentStatus;
    private Date extractionDate;
    private Long contentLength;

    private DocumentBuilder() {}
    public static DocumentBuilder createDoc() {
        return new DocumentBuilder();
    }
    public static DocumentBuilder createDoc(String id) {
        return new DocumentBuilder().withDefaultValues(id);
    }
    public static DocumentBuilder createDoc(Project project, Path path) {
        return new DocumentBuilder().withDefaultValues(Document.getHash(project,path)).with(project).with(path);
    }

    public DocumentBuilder withDefaultValues(String id){
        this.id = id;
        this.charset = Charset.defaultCharset();
        this.content = id;
        this.contentLength = (long) content.getBytes(this.charset).length;
        this.documentStatus = Document.Status.INDEXED;
        this.extractionDate = new Date();
        this.extractionLevel = 0;
        this.path = get("/path/to/").resolve(id);
        this.contentType = "text/plain";
        this.pipelines = new HashSet<>();
        this.project = project("prj");
        this.tags = new HashSet<>();
        return this;
    }

    public DocumentBuilder withId(String id) {
        this.id = id;
        return this;
    }
    public DocumentBuilder with(String content) {
        this.content = content;
        return this;
    }

    public DocumentBuilder with(Path path) {
        this.path = path;
        return this;
    }

    public DocumentBuilder with(Language language) {
        this.language = language;
        return this;
    }

    public DocumentBuilder with(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }

    public DocumentBuilder with(List<Map<String, String>> contentTranslated) {
        this.content_translated = contentTranslated;
        return this;
    }

    public DocumentBuilder ofContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    public DocumentBuilder with(Charset charset) {
        this.charset = charset;
        return this;
    }

    public DocumentBuilder extractedAt(Date extractionDate) {
        this.extractionDate = extractionDate;
        return this;
    }

    public DocumentBuilder with(Pipeline.Type... pipelineTypes) {
        this.pipelines = Arrays.stream(pipelineTypes).collect(toSet());
        return this;
    }
    public DocumentBuilder with(Document.Status documentStatus) {
        this.documentStatus = documentStatus;
        return this;
    }
    public DocumentBuilder with(Project project) {
        this.project = project;
        return this;
    }
    public DocumentBuilder with(Tag... tags) {
        this.tags = Arrays.stream(tags).collect(toSet());
        return this;
    }
    public DocumentBuilder withExtractionLevel(short extractionLevel) {
        this.extractionLevel = extractionLevel;
        return this;
    }
    public DocumentBuilder withContentLength(long contentLength) {
        this.contentLength = contentLength;
        return this;
    }

    public DocumentBuilder withRootId(String rootId) {
        this.rootId = rootId;
        return this;
    }
    public DocumentBuilder withParentId(String parentId) {
        this.parentId = parentId;
        return this;
    }
    public DocumentBuilder withOcrParser(String ocrParser) {
        this.metadata = new HashMap<>(this.metadata);
        this.metadata.put("ocr_parser", ocrParser);
        return this;
    }

    public Document build() {
        if(id == null && project == null && path == null && content == null){
            throw new NullPointerException("Id, Project, Path or content are missing.");
        }
        return new Document(project, id, path, content, content_translated, language,
                charset, contentType, metadata, documentStatus,
                pipelines, extractionDate, parentId, rootId, extractionLevel,
                contentLength, tags);
    }

}
