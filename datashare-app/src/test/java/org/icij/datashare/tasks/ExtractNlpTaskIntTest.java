package org.icij.datashare.tasks;

import com.google.inject.*;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.extract.RedisDocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.*;
import java.util.function.Function;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

@RunWith(Parameterized.class)
public class ExtractNlpTaskIntTest {
    @Mock private Indexer indexer;
    @Mock private AbstractPipeline pipeline;
    private final DocumentCollectionFactory<String> factory;
    private ExtractNlpTask nlpTask;

    public ExtractNlpTaskIntTest(Injector injector) {
        this.factory = injector.getInstance(new Key<>(){});
    }

    @Test(timeout = 2000)
    public void test_loop_consume_two_documents() throws Exception {
        when(pipeline.getType()).thenReturn(Pipeline.Type.CORENLP);
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("content").build();
        when(indexer.get(anyString(), eq("docId"))).thenReturn(doc);

        String queueName = new PipelineHelper(new PropertiesProvider()).getQueueNameFor(Stage.NLP);
        DocumentQueue<String> queue = factory.createQueue(queueName, String.class);
        queue.add("docId");
        queue.add(PipelineTask.STRING_POISON);

        nlpTask.call();

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process(doc);
    }

    @Test(timeout = 2000)
    public void test_progress() throws Exception {
        List<Double> progressValues = Collections.synchronizedList(new ArrayList<>());
        Function<Double, Void> callback = progress -> {
            progressValues.add(progress);
            return null;
        };
        when(pipeline.getType()).thenReturn(Pipeline.Type.CORENLP);
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc1 = createDoc("docId1").build();
        Document doc2 = createDoc("docId2").build();
        Document doc3 = createDoc("docId3").build();
        when(indexer.get(anyString(), eq("docId1"))).thenReturn(doc1);
        when(indexer.get(anyString(), eq("docId2"))).thenReturn(doc2);
        when(indexer.get(anyString(), eq("docId3"))).thenReturn(doc3);

        String queueName = new PipelineHelper(new PropertiesProvider()).getQueueNameFor(Stage.NLP);
        DocumentQueue<String> queue = factory.createQueue(queueName, String.class);
        queue.add("docId1");
        queue.add("docId2");
        queue.add("docId3");
        queue.add(PipelineTask.STRING_POISON);



        new ExtractNlpTask(indexer, pipeline, factory, new Task<>(ExtractNlpTask.class.getName(), User.local(), new HashMap<>() {{
            put("maxContentLength", "32");
        }}), callback).call();

        verify(pipeline, times(3)).initialize(ENGLISH);
        assertThat(progressValues.size()).isGreaterThan(1);
        assertThat(progressValues.get(0)).isLessThan(progressValues.get(progressValues.size() - 1));
        assertThat(progressValues).contains(0.5);
    }

    @Parameterized.Parameters
    public static Collection<Object[]> factories() {
        return asList(new Object[][]{
                {Guice.createInjector(new AbstractModule() {
                    @Override
                    protected void configure() {
                        Config config = new Config();
                        config.useSingleServer().setDatabase(1).setAddress("redis://redis:6379");
                        RedissonClient redissonClient = Redisson.create(config);
                        bind(RedissonClient.class).toInstance(redissonClient);
                        bind(new TypeLiteral<DocumentCollectionFactory<String>>(){}).to(new TypeLiteral<RedisDocumentCollectionFactory<String>>(){});
                    }
                })},
                {Guice.createInjector(new AbstractModule() {
                    @Override
                    protected void configure() {
                        bind(new TypeLiteral<DocumentCollectionFactory<String>>(){}).toInstance(new MemoryDocumentCollectionFactory<>());
                    }
                })}
        });
    }

    @Before
    public void setUp() {
        initMocks(this);
        nlpTask = new ExtractNlpTask(indexer, pipeline, factory, new Task<>(ExtractNlpTask.class.getName(), User.local(), new HashMap<>(){{
            put("maxContentLength", "32");
        }}), null);
    }
}
