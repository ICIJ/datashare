package org.icij.datashare.tasks;

import static org.icij.datashare.test.ElasticsearchRule.TEST_INDEX;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

import java.util.List;
import java.util.Map;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public class BatchNlpTest {
    @Mock
    private Indexer indexer;
    @Mock
    private AbstractPipeline pipeline;
    private AutoCloseable mocks;

    @Before
    public void setUp() {
        this.mocks = openMocks(this);
    }

    @Before
    public void tearDow() throws Exception {
        this.mocks.close();
    }

    @Test(timeout = 2000)
    public void test_batch_nlp() throws Exception {
        // Given
        int maxLength = 20;
        String rootId = "rootId";
        Language language = Language.ENGLISH;
        Document doc0 = createDoc("doc0").with(language).withRootId(rootId)
            .with("hello world").build();
        Document doc1 = createDoc("doc1").with(language).withRootId(rootId)
            .with("this is too long to be processed all at once").build();
        when(pipeline.getType()).thenReturn(Pipeline.Type.CORENLP);
        when(pipeline.initialize(any())).thenReturn(true);

        when(indexer.get(anyString(), anyString(), any(List.class))).thenReturn(doc0, doc1);
        List<CreateNlpBatchesFromIndex.BatchDocument> batchDocs = List.of(
            new CreateNlpBatchesFromIndex.BatchDocument(doc0.getId(), doc0.getRootDocument(), TEST_INDEX, language),
            new CreateNlpBatchesFromIndex.BatchDocument(doc1.getId(), doc1.getRootDocument(), TEST_INDEX, language)
        );
        Map<String, Object> properties = Map.of(
            "docs", batchDocs,
            "pipeline", "OPENNLP",
            "maxLength", maxLength,
            "group", "JAVA"
        );
        BatchNlpTask nlpTask = new BatchNlpTask(
            indexer, pipeline, new Task(BatchNlpTask.class.getName(), new User("test"), properties), null
        );
        // When
        nlpTask.call();
        // Then
        verify(pipeline).process(eq(doc0));
        verify(pipeline).process(eq(doc1), eq(maxLength), eq(0));
        verify(pipeline).process(eq(doc1), eq(maxLength), eq(maxLength));
    }
}
