package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Duplicate;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Test;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.icij.datashare.cli.DatashareCliOptions.ARTIFACT_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT;
import static org.junit.Assert.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class DocumentVerifierTest {

    @Mock private Indexer indexer;
    @Mock private PropertiesProvider propertiesProvider;
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    private DocumentVerifier documentVerifier;

    @Before
    public void setUp() {
        initMocks(this);
        documentVerifier = new DocumentVerifier(indexer, propertiesProvider);
    }

    @Test
    public void test_is_root_document_size_allowed_true_for_root_document() {
        Document doc = DocumentBuilder.createDoc("foo").withContentLength(2L * 1024 * 1024 * 1024).build();
        assertTrue(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    @Test
    public void test_is_root_document_size_allowed_true_for_small_root_document() {
        Project project = new Project("local-datashare");
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(1024).build();
        Document doc = DocumentBuilder.createDoc("foo").with(project).withParentId("bar").withRootId("bar").build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("200G"));

        assertTrue(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    @Test
    public void test_is_root_document_duplicate() {
        Project project = new Project("local-datashare");
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(1024).build();
        Duplicate dup = new Duplicate(rootDoc.getPath(), rootDoc.getId());
        Document doc = DocumentBuilder.createDoc("foo").with(project).withParentId(dup.getId()).withRootId(dup.getId()).build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(indexer.get(project.getId(), dup.getId())).thenReturn(dup);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("200G"));

        assertTrue(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    @Test
    public void test_is_root_document_size_allowed_false_for_big_root_document() {
        Project project = new Project("local-datashare");
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(1024).build();
        Document doc = DocumentBuilder.createDoc("foo").with(project).withParentId("bar").withRootId("bar").build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("200"));

        assertFalse(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    private void indexFile(String index, Document document) {
        when(indexer.get(index, document.getId())).thenReturn(document);
    }

    @Test
    public void test_is_root_document_size_allowed_true_for_embedded_doc_with_cached_raw_artifact() throws Exception {
        Project project = new Project("local-datashare");
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document doc = DocumentBuilder.createDoc(embeddedId).with(project).withParentId("bar").withRootId("bar").build();
        Path rawFile = artifactDir.getRoot().toPath().resolve("local-datashare").resolve("a1").resolve("b2").resolve(embeddedId).resolve("raw");
        Files.createDirectories(rawFile.getParent());
        Files.write(rawFile, "embedded bytes".getBytes());
        Files.write(rawFile.resolveSibling("raw.json"), "{}".getBytes());

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.getRoot().toString()));

        assertTrue(documentVerifier.isRootDocumentSizeAllowed(doc));
        // The cache hit must be checked before the root lookup: a cached embed costs one stat and
        // zero Elasticsearch calls.
        verify(indexer, never()).get(project.getId(), "bar");
    }

    @Test
    public void test_is_root_document_size_allowed_false_for_embedded_doc_without_cached_raw_artifact() {
        Project project = new Project("local-datashare");
        String embeddedId = "a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2c3d4e5f6a7b8c9d0a1b2";
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document doc = DocumentBuilder.createDoc(embeddedId).with(project).withParentId("bar").withRootId("bar").build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));
        when(propertiesProvider.get(ARTIFACT_DIR_OPT)).thenReturn(Optional.of(artifactDir.getRoot().toString()));

        assertFalse(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    @Test
    public void test_is_root_document_size_allowed_false_for_embedded_doc_with_missing_root() {
        Project project = new Project("local-datashare");
        Document doc = DocumentBuilder.createDoc("foo").with(project).withParentId("bar").withRootId("bar").build();

        // A mid-parse OOM writes the root last, so children can be indexed with no root: refuse
        // rather than NPE on the missing root's content length.
        when(indexer.get(project.getId(), "bar")).thenReturn(null);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));

        assertFalse(documentVerifier.isRootDocumentSizeAllowed(doc));
    }

    @Test
    public void test_is_root_document_size_allowed_false_for_big_root_when_no_artifact_dir_configured() {
        Project project = new Project("local-datashare");
        Document rootDoc = DocumentBuilder.createDoc("bar").with(project).withContentLength(2L * 1024 * 1024 * 1024).build();
        Document doc = DocumentBuilder.createDoc("foo").with(project).withParentId("bar").withRootId("bar").build();

        when(indexer.get(project.getId(), "bar")).thenReturn(rootDoc);
        when(propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT)).thenReturn(Optional.of("1G"));

        assertFalse(documentVerifier.isRootDocumentSizeAllowed(doc));
    }
}
