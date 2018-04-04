package org.icij.datashare.text.nlp;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.com.Channel;
import org.icij.datashare.com.Message;
import org.icij.datashare.com.ShutdownMessage;
import org.icij.datashare.com.redis.RedisPublisher;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.file.Paths.get;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.com.Message.Field.DOC_ID;
import static org.icij.datashare.com.Message.Field.R_ID;
import static org.icij.datashare.com.Message.Type.EXTRACT_NLP;
import static org.icij.datashare.text.Document.Status.INDEXED;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.nlp.NlpApp.NLP_PARALLELISM_OPT;
import static org.icij.datashare.text.nlp.Pipeline.Type.CORENLP;
import static org.icij.datashare.text.nlp.Pipeline.Type.OPENNLP;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

public class NlpAppTest {
    @Mock private AbstractPipeline pipeline;
    @Mock private Indexer indexer;
    private RedisPublisher publisher = new RedisPublisher(new PropertiesProvider());
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    @Test
    public void test_subscriber_mode_for_standalone_extraction() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(new NlpApp(indexer, pipeline, new PropertiesProvider(), latch::countDown));
        latch.await(2, SECONDS);

        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id").add(R_ID, "routing"));
        publisher.publish(Channel.NLP, new ShutdownMessage());
        executor.shutdown();
        executor.awaitTermination(5, SECONDS);

        verify(pipeline, times(1)).process(anyString(), anyString(), any(Language.class));
    }

    @Test
    public void test_consumer_mode_for_multithreaded_server_extraction() throws Exception {
        Properties properties = new Properties();
        properties.setProperty(NLP_PARALLELISM_OPT, "2");
        properties.setProperty("messageBusAddress", "redis");
        CountDownLatch latch = new CountDownLatch(1);
        executor.execute(new NlpApp(indexer, pipeline, new PropertiesProvider(properties), latch::countDown));
        latch.await(2, SECONDS);

        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id1").add(R_ID, "routing1"));
        publisher.publish(Channel.NLP, new Message(EXTRACT_NLP).add(DOC_ID, "doc_id2").add(R_ID, "routing2"));
        publisher.publish(Channel.NLP, new ShutdownMessage());
        publisher.publish(Channel.NLP, new ShutdownMessage());
        executor.shutdown();
        executor.awaitTermination(5, SECONDS);

        verify(pipeline, times(2)).process(anyString(), anyString(), any(Language.class));
    }

    @Before
    public void setUp() throws Exception {
        initMocks(this);
        when(indexer.get(anyString(), anyString())).thenReturn(
                new Document(get("doc/path"), "content", FRENCH, Charset.defaultCharset(),
                        "application/pdf", new HashMap<>(), INDEXED));
        when(pipeline.getType()).thenReturn(OPENNLP);
        when(pipeline.initialize(any(Language.class))).thenReturn(true);
        when(pipeline.process(anyString(), anyString(), any(Language.class))).thenReturn(
                new Annotations("doc", CORENLP, FRENCH));
    }

    @After
    public void tearDown() throws Exception {
        reset(indexer, pipeline);
    }
}
