package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.test.ElasticsearchRule;
import org.icij.datashare.text.ContentTypeCategory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT;
import static org.icij.datashare.tasks.PipelineTask.STRING_POISON;

public class CategorizeTaskIntTest {

    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    private MemoryDocumentCollectionFactory<String> documentCollectionFactory;
    private static final String DEFAULT_QUEUE_NAME = "extract:queue:categorize";
    @Before
    public void before() {
        documentCollectionFactory = new MemoryDocumentCollectionFactory<>();
    }

    @After
    public void after() throws IOException {
        es.removeAll();
    }

    @Test
    public void test_categorization() throws Exception {
        //GIVEN
        Document videoDoc = DocumentMother.oneWithContentType("application/mp4");
        Document pdfDoc = DocumentMother.oneWithContentType("application/pdf");
        Document unknownTypeDoc = DocumentMother.oneWithContentType("application/pdf");

        indexAndEnqueueWithPoison(videoDoc, pdfDoc, unknownTypeDoc);
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(DEFAULT_PROJECT_OPT, es.getIndexName())), null);

        //WHEN
        long res = categorizeTask.call();

        //THEN
        assertThat(res).isEqualTo(3);
        DocumentQueue<String> outPutQueue = documentCollectionFactory.createQueue("extract:queue:nlp", String.class);
        assertThat(outPutQueue.size()).isEqualTo(4); // with POISON

        assertThat(((Document) indexer.get(es.getIndexName(), videoDoc.getId())).getContentTypeCategory()).isEqualTo(ContentTypeCategory.VIDEO);
    }

    @Test
    public void test_categorization_with_different_queue_name() throws Exception {
        //GIVEN
        indexAndEnqueueTwoDocuments("foo:categorize");
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(QUEUE_NAME_OPT, "foo", DEFAULT_PROJECT_OPT, es.getIndexName())), null);

        //WHEN
        categorizeTask.call();

        //THEN
        DocumentQueue<String> outputQueue = documentCollectionFactory.createQueue("foo:nlp", String.class);
        assertThat(outputQueue.size()).isEqualTo(3); // with POISON
    }

    @Test
    public void test_update_progress() throws Exception {
        //GIVEN
        indexAndEnqueueTwoDocuments();
        List<Double> progressValues = Collections.synchronizedList(new ArrayList<>());
        Function<Double, Void> callback = progress -> {
            progressValues.add(progress);
            return null;
        };
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(DEFAULT_PROJECT_OPT, es.getIndexName())), callback);

        //WHEN
        categorizeTask.call();

        //THEN
        assertThat(progressValues.size()).isGreaterThan(1);
        assertThat(progressValues.get(0)).isLessThan(0.5); //There are 3 values
    }

    @Test
    public void test_integration_with_enqueue_idx_for_docs_without_contentTypeCategory() throws Exception {
        // GIVEN
        final List<Document> docs = List.of(
                DocumentMother.oneWithContentType("video/mp4"),
                DocumentMother.oneWithContentType("notOkContentType"));

        index(docs);

        Map<String, Object> args = Map.of(
                DEFAULT_PROJECT_OPT, es.getIndexName(),
                "stages", "ENQUEUEIDX,CATEGORIZE");

        // WHEN
        new EnqueueFromIndexTask(documentCollectionFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), args), null).call();

        documentCollectionFactory.createQueue("extract:queue:categorize", String.class).add(STRING_POISON);

        new CategorizeTask(indexer, documentCollectionFactory,
                new Task<>(CategorizeTask.class.getName(), "categorizeTask2", User.local(), args), null).call();

        // THEN
        Document doc1 = indexer.get(es.getIndexName(), docs.get(0).getId());
        Document doc2 = indexer.get(es.getIndexName(), docs.get(1).getId());

        assertThat(doc1.getContentTypeCategory()).isEqualTo(ContentTypeCategory.VIDEO);
        assertThat(doc2.getContentTypeCategory()).isEqualTo(ContentTypeCategory.OTHER);
    }

    @Test
    public void test_integration_with_enqueue_idx_to_update_contentTypeCategory() throws Exception {
        // GIVEN
        final Document toUpdateDoc =
                DocumentBuilder.from(DocumentMother.oneWithContentType("video/mp4")).with(ContentTypeCategory.OTHER).build();

        index(toUpdateDoc);

        Map<String, Object> args = Map.of(
                DEFAULT_PROJECT_OPT, es.getIndexName(),
                "stages", "ENQUEUEIDX,CATEGORIZE");

        // WHEN
        new EnqueueFromIndexTask(documentCollectionFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), args), null).call();

        documentCollectionFactory.createQueue("extract:queue:categorize", String.class).add(STRING_POISON);

        new CategorizeTask(indexer, documentCollectionFactory,
                new Task<>(CategorizeTask.class.getName(), "categorizeTask2", User.local(), args), null).call();
        Document updatedDoc = indexer.get(es.getIndexName(), toUpdateDoc.getId());
        // THEN
        assertThat(updatedDoc.getContentTypeCategory()).isEqualTo(ContentTypeCategory.VIDEO);
    }

    private static class DocumentMother {
        public static Document oneWithContentType(String contentType){
            return DocumentBuilder.createDoc(UUID.randomUUID().toString()).ofContentType(contentType).build();
        }
    }

    private void indexAndEnqueueWithPoison(String queueName, Document... documents) {

        DocumentQueue<String> queue = documentCollectionFactory.createQueue(queueName, String.class);

        Arrays.stream(documents).forEach(d -> {
            index(d);
            queue.add(d.getId());
        });
        queue.add(STRING_POISON);
    }

    private void indexAndEnqueueWithPoison(Document... documents) {
        indexAndEnqueueWithPoison(DEFAULT_QUEUE_NAME, documents);
    }

    private void indexAndEnqueueTwoDocuments() {
        indexAndEnqueueTwoDocuments(DEFAULT_QUEUE_NAME);
    }

    private void indexAndEnqueueTwoDocuments(String queueName) {
        indexAndEnqueueWithPoison(queueName,
                DocumentMother.oneWithContentType("application/pdf"),
                DocumentMother.oneWithContentType("notOkContentType"));
    }

    private void index(Document doc) throws RuntimeException {
        try {
            indexer.add(es.getIndexName(), doc);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void index(List<Document> docs){
        docs.forEach(this::index);
    }


}