package org.icij.datashare.tasks;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import java.util.stream.Stream;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.*;
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

import java.util.Collection;
import java.util.HashMap;

import static java.util.Arrays.asList;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.icij.datashare.text.Language.FRENCH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
    public void test_loop_consume_two_doc_texts() throws Exception {
        when(pipeline.getType()).thenReturn(Pipeline.Type.CORENLP);
        when(pipeline.initialize(any())).thenReturn(true);
        Document enDoc = createDoc("enId").with("content").with(ENGLISH).build();
        Document frDoc = createDoc("fr").with("contenu").with(FRENCH).build();
        when(indexer.get(anyString(), eq("enId"))).thenReturn(enDoc);
        when(indexer.get(anyString(), eq("frId"))).thenReturn(frDoc);

        String queueName = new PipelineHelper(new PropertiesProvider()).getQueueNameFor(Stage.NLP);
        DocumentQueue<String> queue = factory.createQueue(queueName, String.class);
        queue.add("enId");
        queue.add("frId");
        queue.add(PipelineTask.STRING_POISON);

        nlpTask.call();

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).processText(any(Stream.class), eq(ENGLISH));
        verify(pipeline).terminate(ENGLISH);
        verify(pipeline).processText(any(Stream.class), eq(ENGLISH));
        verify(pipeline).initialize(FRENCH);
        verify(pipeline).terminate(FRENCH);
    }

    @Test(timeout = 2000)
    public void test_loop_consume_two_docs() throws Exception {
        when(pipeline.getType()).thenReturn(Pipeline.Type.EMAIL);
        when(pipeline.initialize(any())).thenReturn(true);
        Document enDoc = createDoc("enId").with(ENGLISH).build();
        Document frDoc = createDoc("frId").with(FRENCH).build();
        when(indexer.get(anyString(), eq("enId"))).thenReturn(enDoc);
        when(indexer.get(anyString(), eq("frId"))).thenReturn(frDoc);

        String queueName = new PipelineHelper(new PropertiesProvider()).getQueueNameFor(Stage.NLP);
        DocumentQueue<String> queue = factory.createQueue(queueName, String.class);
        queue.add("enId");
        queue.add("frId");
        queue.add(PipelineTask.STRING_POISON);

        nlpTask.call();

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).processDoc(eq(enDoc));
        verify(pipeline).terminate(ENGLISH);
        verify(pipeline).initialize(FRENCH);
        verify(pipeline).processDoc(eq(frDoc));
        verify(pipeline).terminate(FRENCH);
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
            put("maxContentLength", "8");
            put("batchSize", "1");
        }}), null);
    }
}
