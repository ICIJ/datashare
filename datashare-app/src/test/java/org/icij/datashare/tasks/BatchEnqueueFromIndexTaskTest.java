package org.icij.datashare.tasks;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;

import co.elastic.clients.elasticsearch._types.Refresh;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.ClassRule;
import org.junit.Test;

public class BatchEnqueueFromIndexTaskTest {
    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    @After public void tearDown() throws IOException { es.removeAll();}

    @Test
    public void test_queue_for_batch_nlp_by_batch() throws Exception {
        // Given
        int numDocs = 20;
        for (int i = 0; i < numDocs; i++) {
            indexer.add(TEST_INDEX, createDoc("doc" + i).with(Pipeline.Type.CORENLP).build());
        }
        int batchSize = 3;
        Map<String, Object> properties = Map.of(
                "defaultProject", "test-datashare",
                "stages", "BATCHENQUEUEIDX",
                "queueName", "test:queue",
                "nlpPipeline", "OPENNLP",
                "batchSize", batchSize
        );
        MemoryDocumentCollectionFactory<List> factory = new MemoryDocumentCollectionFactory<>();
        BatchEnqueueFromIndexTask enqueueFromIndex = new BatchEnqueueFromIndexTask(factory, indexer, new Task<>(BatchEnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        // When
        enqueueFromIndex.call();
        ArrayList<List> batches = new ArrayList<>();
        factory.queues.get("test:queue:batchnlp").drainTo(batches);
        // Then
        ArrayList<List> expected = new ArrayList<>(11 + 1);
        for (int i = 0; i < numDocs; i += batchSize) {
            expected.add(IntStream.range(i, Integer.min(i + batchSize, numDocs)).mapToObj(ix -> "doc" + ix).toList());
        }
        expected.add(List.of("POISON"));
        assertThat(batches).isEqualTo(expected);
    }

    @Test
    public void test_queue_for_batch_nlp_by_batch_with_body() throws Exception {
        // Given
        indexer.add(TEST_INDEX, createDoc("my_id").with("this is my precious doc")
                .with(Pipeline.Type.CORENLP).with(project(TEST_INDEX)).build());
        indexer.add(TEST_INDEX, createDoc("my_other_id").with("this is not my precious doc")
            .withExtractionLevel((short) 1)
            .with(Pipeline.Type.CORENLP).with(project(TEST_INDEX)).build());
        Map<String, Object> properties = Map.of(
                "defaultProject", "test-datashare",
                "stages", "ENQUEUEIDX",
                "queueName", "test:queue",
                "searchQuery", """
                        {
                            "match": {
                              "extractionLevel": 0
                            }
                        }
                        """
        );
        MemoryDocumentCollectionFactory<List> factory = new MemoryDocumentCollectionFactory<>();
        BatchEnqueueFromIndexTask enqueueFromIndex = new BatchEnqueueFromIndexTask(factory, indexer, new Task<>(BatchEnqueueFromIndexTask.class.getName(), new User("test"), properties), null);
        // When
        enqueueFromIndex.call();
        ArrayList<List> batches = new ArrayList<>();
        factory.queues.get("test:queue:batchnlp").drainTo(batches);
        // Then
        List<List<String>> expected = List.of(List.of("my_id"), List.of("POISON"));
        assertThat(batches).isEqualTo(expected);
    }
}
