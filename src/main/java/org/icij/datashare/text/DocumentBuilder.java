package org.icij.datashare.text;

import org.icij.datashare.text.nlp.Pipeline;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

import static java.nio.file.Paths.get;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Project.project;

public class DocumentBuilder {
    private String id;
    private String content;
    private Path path;
    private Map<String, Object> metadata = new HashMap<>();
    private String mimeType;
    private Set<Pipeline.Type> pipelines;
    private String rootId = null;

    public static DocumentBuilder createDoc(String id) {
        return new DocumentBuilder(id);
    }

    private DocumentBuilder(String id) {
        this.id = id;
        this.content = id;
        this.path = get("/path/to/").resolve(id);
        this.mimeType = "text/plain";
        this.pipelines = new HashSet<>();
    }

    public DocumentBuilder with(String content) {
        this.content = content;
        return this;
    }

    public DocumentBuilder with(Path path) {
        this.path = path;
        return this;
    }

    public DocumentBuilder with(Map<String, Object> metadata) {
        this.metadata = metadata;
        return this;
    }

    public Document build() {
        return new Document(project("prj"), id, path, content, FRENCH, Charset.defaultCharset(),
                         mimeType, metadata, Document.Status.INDEXED,
                        pipelines, new Date(), rootId, rootId, (short) 0, 123L);
    }

    public DocumentBuilder ofMimeType(String mimeType) {
        this.mimeType=mimeType;
        return this;
    }

    public DocumentBuilder with(Pipeline.Type ... pipelineTypes) {
        this.pipelines = Arrays.stream(pipelineTypes).collect(toSet());
        return this;
    }

    public DocumentBuilder withRootId(String rootId) {
        this.rootId=rootId;
        return this;
    }
}
