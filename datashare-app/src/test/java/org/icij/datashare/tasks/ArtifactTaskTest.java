package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.LogbackCapturingRule;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.slf4j.event.Level;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactTaskTest {
    private static final String EMBEDDED_DOC_SHA256 = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    @Rule public LogbackCapturingRule logback = new LogbackCapturingRule();
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();

    @Test(expected = IllegalArgumentException.class)
    public void test_missing_artifact_dir() {
        new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of()), new Task<>(ArtifactTask.class.getName(), User.local(), Map.of()), null);
    }

    @Test(timeout = 10000)
    public void test_create_artifact_cache_one_file() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb").toFile()).isDirectory();
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw.json").toFile()).isFile();
    }

    @Test(timeout = 10000)
    public void test_skip_document_not_found_in_index_with_warning() throws Exception {
        indexEmbeddedDoc();
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add("unknownId");
        queue.add(EMBEDDED_DOC_SHA256);

        Long numberOfDocuments = runArtifactTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(logback.logs(Level.WARN)).contains("document <unknownId> could not be retrieved from index prj (missing document or index fetch error), skipping");
        assertThat(logback.logs(Level.ERROR)).contains("1 document(s) could not be retrieved from index prj and got no artifact cache, re-run the ARTIFACT stage for them");
        assertThat(logback.logs(Level.ERROR)).excludes("error in ArtifactTask loop");
    }

    @Test(timeout = 10000)
    public void test_entry_with_routing_fetches_document_with_root_id() throws Exception {
        indexEmbeddedDoc("rootId");
        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(EMBEDDED_DOC_SHA256 + "|rootId");

        Long numberOfDocuments = runArtifactTask();

        verify(mockEs).get("prj", EMBEDDED_DOC_SHA256, "rootId", List.of("content", "content_translated"));
        assertThat(numberOfDocuments).isEqualTo(1);
    }

    private void indexEmbeddedDoc() throws URISyntaxException {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);
    }

    private void indexEmbeddedDoc(String rootId) throws URISyntaxException {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        mockIndexer.indexFile("prj", EMBEDDED_DOC_SHA256, path, "message/rfc822", rootId);
    }

    private Long runArtifactTask() throws Exception {
        return new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1"
                )),
                new Task<>(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
                .call();
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockIndexer = new MockIndexer(mockEs);
    }
}
