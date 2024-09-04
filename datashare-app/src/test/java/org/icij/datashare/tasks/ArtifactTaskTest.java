package org.icij.datashare.tasks;


import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.function.Pair;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.MockitoAnnotations.initMocks;

public class ArtifactTaskTest {
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    @Mock Indexer mockEs;
    MockIndexer mockIndexer;
    private final MemoryDocumentCollectionFactory<Pair<String, String>> factory = new MemoryDocumentCollectionFactory<>();

    @Test(expected = IllegalArgumentException.class)
    public void test_missing_artifact_dir() {
        new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of()), new Task<>(ArtifactTask.class.getName(), User.local(), Map.of()), null);
    }

    @Test
    public void test_create_artifact_cache_one_file() throws Exception {
        Path path = Path.of(getClass().getResource("/docs/embedded_doc.eml").getPath());
        mockIndexer.indexFile("prj", "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e", path, "application/pdf", "embedded_doc");

        DocumentQueue<Pair<String, String>> queue = factory.createQueue("extract:queue:artifact", (Class<Pair<String, String>>)(Object)Pair.class);
        queue.add(new Pair<>("6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e", "embedded_doc"));
        queue.add(new Pair<>("POISON", "POISON"));

        Long numberOfDocuments = new ArtifactTask(factory, mockEs, new PropertiesProvider(Map.of()),
                new Task<>(ArtifactTask.class.getName(), User.local(), Map.of("artifactDir", artifactDir.getRoot().toString(), "defaultProject", "prj")), null)
                .call();

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