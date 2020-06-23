package org.icij.datashare;

import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchAppTestInt {
    BlockingQueue<String> batchSearchQueue = new LinkedBlockingQueue<>();
    @Mock BatchSearchRunner batchSearchRunner;

    @Test
    public void test_start_method_without_batch_queue_type_parameter() {
        BatchSearchApp batchSearchApp = BatchSearchApp.create(PropertiesProvider.fromMap(new HashMap<String, String>() {{ put("mode", "BATCH");}}));
        assertThat(batchSearchApp.batchSearchQueue).isNotNull();
        assertThat(batchSearchApp.batchSearchQueue.getClass()).isEqualTo(LinkedBlockingQueue.class);
    }

    @Test
    public void test_start_method_with__redis_batch_queue_type_parameter()  {
        Properties properties = PropertiesProvider.fromMap(new HashMap<String, String>() {{
            put("batchQueueType", "org.icij.datashare.extract.RedisBlockingQueue");
            put("mode", "BATCH");
        }});
        BatchSearchApp batchSearchApp = BatchSearchApp.create(properties);
        assertThat(batchSearchApp.batchSearchQueue.getClass()).isEqualTo(RedisBlockingQueue.class);
    }

    @Test
    public void test_main_loop() {
        when(batchSearchRunner.run("batchSearch.uuid")).thenReturn(12);
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);
        batchSearchQueue.add("batchSearch.uuid");
        batchSearchQueue.add(BatchSearchApp.POISON);

        app.run();

        verify(batchSearchRunner).run("batchSearch.uuid");
        verify(batchSearchRunner, never()).run(BatchSearchApp.POISON);
    }

    @Test
    public void test_main_loop_with_batch_not_queued() {
        when(batchSearchRunner.run("batchSearch.uuid")).thenReturn(12);
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);
        batchSearchQueue.add("batchSearch.uuid");
        batchSearchQueue.add(BatchSearchApp.POISON);

        app.run();

        verify(batchSearchRunner).run("batchSearch.uuid");
        verify(batchSearchRunner, never()).run(BatchSearchApp.POISON);
    }

    @Test
    public void test_main_loop_unknown_batch_id() {
        when(batchSearchRunner.run("test")).thenThrow(new JooqBatchSearchRepository.BatchNotFoundException("test JooqBatchSearch"));
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);
        batchSearchQueue.add("test");
        batchSearchQueue.add(BatchSearchApp.POISON);

        app.run();

        verify(batchSearchRunner).run("test");
    }

    @Test
    public void test_main_loop_runtime_exception() {
        when(batchSearchRunner.run("test")).thenThrow(new RuntimeException("test runtime"));
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);
        batchSearchQueue.add("test");
        batchSearchQueue.add(BatchSearchApp.POISON);

        app.run();

        verify(batchSearchRunner).run("test");
    }

    @Before
    public void setUp() {
        initMocks(this);
    }
}
