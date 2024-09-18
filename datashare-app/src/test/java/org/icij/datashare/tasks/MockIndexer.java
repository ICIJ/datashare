package org.icij.datashare.tasks;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;

public class MockIndexer {
    private final Indexer mockIndexer;

    public MockIndexer(Indexer mockIndexer) {
        this.mockIndexer = mockIndexer;
    }

    public void indexFile(String index, String _id, Path path, String contentType) {
        indexFile(index, _id, path, contentType, null);
    }
    public void indexFile(String index, String _id, Path path, String contentType, String routing) {
        Document document = DocumentBuilder.createDoc(_id)
                .with(path)
                .with(new Project(index))
                .ofContentType(contentType)
                .withParentId(routing)
                .withRootId(routing)
                .withContentLength(10)
                .build();
        if (routing == null) {
            indexFile(index, document);
        } else {
            Document rootDocument = DocumentBuilder.createDoc(routing).with(path).with(new Project(index)).build();
            indexFile(index, rootDocument, document);
        }
    }

    public void indexFile(String index, Document rootDocument, Document document) {
        List<String> sourceExcludes = List.of("content", "content_translated");
        when(mockIndexer.get(index, rootDocument.getId())).thenReturn(rootDocument);
        when(mockIndexer.get(index, document.getId(), document.getRootDocument())).thenReturn(document);
        when(mockIndexer.get(index, document.getId(), document.getRootDocument(), sourceExcludes)).thenReturn(document);
    }

    public void indexFile(String index, Document document) {
        List<String> sourceExcludes = List.of("content", "content_translated");
        when(mockIndexer.get(index, document.getId())).thenReturn(document);
        when(mockIndexer.get(index, document.getId(), sourceExcludes)).thenReturn(document);
        when(mockIndexer.get(index, document.getId(), document.getId(), sourceExcludes)).thenReturn(document);
    }

    public static void write(File file, String content) throws IOException {
        file.toPath().getParent().toFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(UTF_8));
    }
}
