package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactTaskTest {
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();

    @Test(expected = IllegalArgumentException.class)
    public void test_missing_artifact_dir() {
        new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of()), new Task(ArtifactTask.class.getName(), User.local(), Map.of()), null);
    }

    @Test(timeout = 10000)
    public void test_create_artifact_cache_one_file() throws Exception {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).getPath());
        String sha256 = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
        mockIndexer.indexFile("prj", sha256, path, "message/rfc822");

        DocumentQueue<String> queue = factory.createQueue("extract:queue:artifact", String.class);
        queue.add(sha256);

        Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of(
                "artifactDir", artifactDir.getRoot().toString(),
                "defaultProject", "prj",
                "pollingInterval", "1"
                )),
                new Task(ArtifactTask.class.getName(), User.local(), new HashMap<>()), null)
                .runTask();

        assertThat(numberOfDocuments).isEqualTo(1);
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb").toFile()).isDirectory();
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw").toFile()).isFile();
        assertThat(artifactDir.getRoot().toPath().resolve("prj/6a/bb/6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e/raw.json").toFile()).isFile();
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        mockIndexer = new MockIndexer(mockEs);
    }
}