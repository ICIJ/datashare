package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

import org.icij.datashare.test.LogbackAppenderWrapper;

import java.io.IOException;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.fail;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;

public class EnqueueFromIndexTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_size_of_search() throws Exception {
        for (int i = 0; i < 20; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i).with(Pipeline.Type.CORENLP).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.OPENNLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer, new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:nlp")).hasSize(20);
    }

    @Test
    public void test_with_query_body() throws Exception {
        indexer.add(es.getIndexName(), createDoc("my_id").with("this is my precious doc")
                .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build()); // because default is CORENLP so it should fail as of now
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                "searchQuery", """
                        {
                            "match": {
                              "extractionLevel": 0
                            }
                        }
                        """);

        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer, new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:nlp")).hasSize(1);
    }

    @Test
    public void test_with_query_string() throws Exception {
        indexer.add(es.getIndexName(), createDoc("my_id").with("this is my precious doc")
                .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build()); // because default is CORENLP so it should fail as of now
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                "searchQuery", "extractionLevel:0");

        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer, new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:nlp")).hasSize(1);
    }

    @Test
    public void test_no_query_targeting_artifact_ignores_nlp_pipeline_filter() throws Exception {
        for (int i = 0; i < 5; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i)
                    .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ARTIFACT,ENQUEUEIDX",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:artifact")).hasSize(5);
    }

    @Test
    public void test_next_stage_option_overrides_the_stages_chain() throws Exception {
        for (int i = 0; i < 5; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i)
                    .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "nextStage", "ARTIFACT",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        // without the override the chain falls back to NLP: docs land on test:queue:nlp and the
        // CORENLP filter excludes all of them
        assertThat(factory.queues.get("test:queue:artifact")).hasSize(5);
    }

    @Test
    public void test_unknown_next_stage_fails_the_run_not_the_construction() throws Exception {
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "nextStage", "NOT_A_STAGE",
                "queueName", "test:queue");
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        // a constructor throw would become a requeue-forever NackException, so it must not throw here
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        try {
            enqueueFromIndex.call();
            fail("an unknown nextStage value must fail the run");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("NOT_A_STAGE");
        }
    }

    @Test
    public void test_next_stage_without_queue_consumer_is_rejected() throws Exception {
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "nextStage", "CREATENLPBATCHESFROMIDX",
                "queueName", "test:queue");
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        try {
            enqueueFromIndex.call();
            // CreateNlpBatchesFromIndex reads the index, it never drains test:queue:createnlpbatchesfromidx
            fail("a stage with no queue consumer must be rejected");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("CREATENLPBATCHESFROMIDX");
        }
    }

    @Test
    public void test_next_stage_creates_no_queue_for_the_chain_default() throws Exception {
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "nextStage", "ARTIFACT",
                "queueName", "test:queue");
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null).call();
        assertThat(factory.queues.keySet()).excludes("test:queue:nlp");
    }

    @Test
    public void test_next_stage_before_enqueueidx_is_rejected() throws Exception {
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "nextStage", "INDEX",
                "queueName", "test:queue");
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        try {
            enqueueFromIndex.call();
            // INDEX consumes Paths, so doc ids enqueued there poison that stage rather than failing loudly
            fail("a stage running before ENQUEUEIDX must be rejected");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("INDEX");
        }
    }

    @Test
    public void test_no_query_targeting_nlp_still_excludes_already_processed() throws Exception {
        for (int i = 0; i < 5; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i)
                    .with(Pipeline.Type.CORENLP).with(project(es.getIndexName())).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        EnqueueFromIndexTask enqueueFromIndex = new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        enqueueFromIndex.call();
        assertThat(factory.queues.get("test:queue:nlp")).isNullOrEmpty();
    }

    @Test
    public void test_log_displays_correct_document_count() throws Exception {
        LogbackAppenderWrapper logWrapper = new LogbackAppenderWrapper();
        for (int i = 0; i < 3; i++) {
            indexer.add(es.getIndexName(), createDoc("doc" + i).with(project(es.getIndexName())).build());
        }
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                "searchQuery", "{\"match_all\":{}}");
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
        new EnqueueFromIndexTask(factory, indexer, new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null).call();
        assertThat(logWrapper.logs().stream().anyMatch(log -> log.contains("3 documents found"))).isTrue();
    }

    @Test
    public void test_embedded_document_is_enqueued_with_its_root_id() throws Exception {
        Document root = createDoc("root_doc").with(project(es.getIndexName())).build();
        Document embedded = createDoc("embedded_doc").with(project(es.getIndexName()))
                .withParentId(root.getId()).withRootId(root.getId()).build();
        indexer.add(es.getIndexName(), root);
        indexer.add(es.getIndexName(), embedded);
        Map<String, Object> properties = Map.of(
                "defaultProject", es.getIndexName(),
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                NLP_PIPELINE_OPT, Pipeline.Type.OPENNLP.name());
        MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();

        new EnqueueFromIndexTask(factory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), new User("test"), properties), null).call();

        assertThat(factory.queues.get("test:queue:nlp"))
                .contains(root.getId(), embedded.getId() + "|" + root.getId());
    }
}
