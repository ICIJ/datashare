package org.icij.datashare.utils;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Duplicate;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Test;
import org.junit.Before;
import org.mockito.Mock;

import java.util.Optional;

import static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT;
import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class DocumentVerifierTest {

    @Mock private Indexer indexer;
    @Mock private PropertiesProvider propertiesProvider;
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
}
