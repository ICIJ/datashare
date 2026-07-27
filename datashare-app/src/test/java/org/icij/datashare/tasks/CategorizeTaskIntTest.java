package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.Refresh;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
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

public class CategorizeTaskIntTest {

    @ClassRule
    public static ElasticsearchRule es = new ElasticsearchRule();
    private final ElasticsearchIndexer indexer = new ElasticsearchIndexer(es.client, new PropertiesProvider()).withRefresh(Refresh.True);

    private MemoryDocumentCollectionFactory<String> documentCollectionFactory;
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();
    private static final String DEFAULT_INPUT_QUEUE_NAME = "extract:queue:categorize";

    @Before
    public void setUp() {
        documentCollectionFactory = new MemoryDocumentCollectionFactory<>();
    }

    @After
    public void tearDown() throws IOException {
        es.removeAll();
    }

    @Test
    public void test_categorization() throws Exception {
        //GIVEN
        Document videoDoc = aDocWithContentType("application/mp4");
        Document pdfDoc = aDocWithContentType("application/pdf");
        Document unknownTypeDoc = aDocWithContentType("other-that-is-not_Kn%wn");

        indexAndEnqueue(videoDoc, pdfDoc, unknownTypeDoc);
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, taskRepository, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(DEFAULT_PROJECT_OPT, es.getIndexName())), NO_OP_PROGRESS);

        //WHEN
        long res = categorizeTask.call();

        //THEN
        assertThat(res).isEqualTo(3);
        DocumentQueue<String> outPutQueue = documentCollectionFactory.createQueue("extract:queue:nlp", String.class);
        assertThat(outPutQueue.size()).isEqualTo(3);

        assertThat(((Document) indexer.get(es.getIndexName(), videoDoc.getId())).getContentTypeCategory()).isEqualTo(ContentTypeCategory.VIDEO);
        assertThat(((Document) indexer.get(es.getIndexName(), pdfDoc.getId())).getContentTypeCategory()).isEqualTo(ContentTypeCategory.DOCUMENT);
        assertThat(((Document) indexer.get(es.getIndexName(), unknownTypeDoc.getId())).getContentTypeCategory()).isEqualTo(ContentTypeCategory.OTHER);

    }

    @Test
    public void test_categorization_with_different_queue_name() throws Exception {
        //GIVEN
        indexAndEnqueueTwoDocuments("foo:categorize");
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, taskRepository, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(QUEUE_NAME_OPT, "foo", DEFAULT_PROJECT_OPT, es.getIndexName())), NO_OP_PROGRESS);

        //WHEN
        categorizeTask.call();

        //THEN
        DocumentQueue<String> outputQueue = documentCollectionFactory.createQueue("foo:nlp", String.class);
        assertThat(outputQueue.size()).isEqualTo(2);
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
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, taskRepository, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(DEFAULT_PROJECT_OPT, es.getIndexName())), callback);

        //WHEN
        categorizeTask.call();

        //THEN
        assertThat(progressValues.size()).isGreaterThan(1);
        assertThat(progressValues.get(0)).isLessThan(progressValues.get(progressValues.size() - 1));
        assertThat(progressValues).contains(1.0);
    }

    @Test
    public void test_receiving_null_from_indexer() throws Exception {
        // GIVEN
        // A document that will not be in the index
        enqueue(aDoc());
        CategorizeTask categorizeTask = new CategorizeTask(indexer, documentCollectionFactory, taskRepository, new Task<>(CategorizeTask.class.getName(),
                "categorizeTask1", User.local(), Map.of(DEFAULT_PROJECT_OPT, es.getIndexName())), NO_OP_PROGRESS);

        // WHEN
        long res = categorizeTask.call();

        // THEN
        assertThat(res).isEqualTo(1);
        DocumentQueue<String> outPutQueue = documentCollectionFactory.createQueue("extract:queue:nlp", String.class);
        assertThat(outPutQueue.size()).isEqualTo(1);
    }

    @Test
    public void test_integration_with_enqueue_idx_for_docs_without_contentTypeCategory() throws Exception {
        // GIVEN
        final List<Document> docs = List.of(
                aDocWithContentType("video/mp4"),
                aDocWithContentType("notOkContentType"));

        index(docs);

        Map<String, Object> args = Map.of(
                DEFAULT_PROJECT_OPT, es.getIndexName(),
                "stages", "ENQUEUEIDX,CATEGORIZE");

        // WHEN
        new EnqueueFromIndexTask(documentCollectionFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), args), null).call();

        new CategorizeTask(indexer, documentCollectionFactory, taskRepository,
                new Task<>(CategorizeTask.class.getName(), "categorizeTask2", User.local(), args), NO_OP_PROGRESS).call();

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
                DocumentBuilder.from(aDocWithContentType("video/mp4")).with(ContentTypeCategory.OTHER).build();

        index(toUpdateDoc);

        Map<String, Object> args = Map.of(
                DEFAULT_PROJECT_OPT, es.getIndexName(),
                "stages", "ENQUEUEIDX,CATEGORIZE");

        // WHEN
        new EnqueueFromIndexTask(documentCollectionFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), args), null).call();

        new CategorizeTask(indexer, documentCollectionFactory, taskRepository,
                new Task<>(CategorizeTask.class.getName(), "categorizeTask2", User.local(), args), NO_OP_PROGRESS).call();
        Document updatedDoc = indexer.get(es.getIndexName(), toUpdateDoc.getId());
        // THEN
        assertThat(updatedDoc.getContentTypeCategory()).isEqualTo(ContentTypeCategory.VIDEO);
    }

    @Test
    public void test_integration_with_enqueue_idx_for_embedded_docs() throws Exception {
        // GIVEN a root document and an embedded document routed under it
        Document root = aDocWithContentType("message/rfc822");
        Document embedded = DocumentBuilder.createDoc(UUID.randomUUID().toString())
                .ofContentType("application/pdf")
                .withParentId(root.getId())
                .withRootId(root.getId())
                .build();
        index(List.of(root, embedded));

        Map<String, Object> args = Map.of(
                DEFAULT_PROJECT_OPT, es.getIndexName(),
                "stages", "ENQUEUEIDX,CATEGORIZE");

        // WHEN
        new EnqueueFromIndexTask(documentCollectionFactory, indexer,
                new Task<>(EnqueueFromIndexTask.class.getName(), User.local(), args), null).call();

        assertThat(documentCollectionFactory.queues.get("extract:queue:categorize"))
                .contains(embedded.getId() + "|" + root.getId());

        new CategorizeTask(indexer, documentCollectionFactory, taskRepository,
                new Task<>(CategorizeTask.class.getName(), "categorizeTask3", User.local(), args), NO_OP_PROGRESS).call();

        // THEN the embedded doc got categorized (fetched and updated with routing)...
        Document updated = indexer.get(es.getIndexName(), embedded.getId(), root.getId());
        assertThat(updated.getContentTypeCategory()).isEqualTo(ContentTypeCategory.DOCUMENT);
        // ...and the encoded entry is forwarded downstream
        assertThat(documentCollectionFactory.queues.get("extract:queue:nlp"))
                .contains(embedded.getId() + "|" + root.getId());
    }

    private static Document aDocWithContentType(String contentType){
        return DocumentBuilder.createDoc(UUID.randomUUID().toString()).ofContentType(contentType).build();
    }

    private static Document aDoc(){
        return DocumentBuilder.createDoc(UUID.randomUUID().toString()).build();
    }

    private void indexAndEnqueue(String queueName, Document... documents) {
        Arrays.stream(documents).forEach(this::index);
        enqueue(queueName, documents);
    }

    private void indexAndEnqueue(Document... documents) {
        indexAndEnqueue(DEFAULT_INPUT_QUEUE_NAME, documents);
    }

    private void indexAndEnqueueTwoDocuments() {
        indexAndEnqueueTwoDocuments(DEFAULT_INPUT_QUEUE_NAME);
    }

    private void indexAndEnqueueTwoDocuments(String queueName) {
        indexAndEnqueue(queueName,
                aDocWithContentType("application/pdf"),
                aDocWithContentType("notOkContentType"));
    }

    private void enqueue(String queueName, Document... documents){
        DocumentQueue<String> queue = documentCollectionFactory.createQueue(queueName, String.class);
        Arrays.stream(documents).forEach(d -> queue.add(d.getId()));
    }

    private void enqueue(Document... documents){
        enqueue(DEFAULT_INPUT_QUEUE_NAME, documents);
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

    private static final Function<Double, Void> NO_OP_PROGRESS = d -> null;

}