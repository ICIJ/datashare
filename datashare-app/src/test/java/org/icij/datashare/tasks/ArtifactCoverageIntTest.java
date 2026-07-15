package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.ArtifactCoverageChecker;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

/** End-to-end pipeline coverage audit: corpus -> INDEX -> ENQUEUEIDX -> ARTIFACT -> checker.
 *  A failure here is an AUDIT FINDING (a real hole left by the ARTIFACT stage), not a broken
 *  test: the assertion must stay red until the Phase 3 fix lands. */
public class ArtifactCoverageIntTest {
    @Rule public ElasticsearchRule es = new ElasticsearchRule();
    @Rule public TemporaryFolder corpusDir = new TemporaryFolder();
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();

    @Test(timeout = 300_000)
    public void every_indexed_document_has_a_terminal_manifest_and_servable_source() throws Exception {
        Map<String, Object> map = new HashMap<>(Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ARTIFACT,ENQUEUEIDX",
                "queueName", "test:queue",
                "artifactDir", artifactDir.getRoot().toString(),
                "pollingInterval", "1",
                "parallelism", "2"));
        PropertiesProvider props = new PropertiesProvider(map);

        // 1. corpus on the INDEX queue
        MemoryDocumentCollectionFactory<Path> indexQueueFactory = new MemoryDocumentCollectionFactory<>();
        MemoryDocumentCollectionFactory<String> stringQueueFactory = new MemoryDocumentCollectionFactory<>();
        DocumentQueue<Path> indexQueue = indexQueueFactory.createQueue(
                new PipelineHelper(props).getQueueNameFor(Stage.INDEX), Path.class);
        NastyCorpus.buildInto(corpusDir.getRoot().toPath()).forEach(indexQueue::add);

        // 2. INDEX with a real spewer (same construction as IndexTaskIntTest:59-60)
        ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);
        ElasticsearchSpewer spewer = new ElasticsearchSpewer(indexer, stringQueueFactory,
                text -> Language.ENGLISH, new FieldNames(), props);
        new IndexTask(spewer, indexQueueFactory, new Task<>(IndexTask.class.getName(), User.local(), map), null).call();

        // 3. ENQUEUEIDX -> ARTIFACT (queue test:queue:artifact), then the task itself
        new EnqueueFromIndexTask(stringQueueFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), map), null).call();
        new ArtifactTask(stringQueueFactory, indexer, props,
                new Task<>(ArtifactTask.class.getName(), User.local(), map), null).call();

        // 4. coverage
        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(indexer, new SourceExtractor(props))
                .check(Project.project(es.getIndexName()), artifactDir.getRoot().toPath(), 100);
        assertThat(report.checked()).isGreaterThan(200); // wide.zip alone contributes ~200 children
        assertThat(report.summary()).isEqualTo(String.format("checked %d document(s), 0 hole(s)%n", report.checked()));
    }
}
