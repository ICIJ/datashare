package org.icij.datashare;

import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.db.JooqBatchSearchRepository;
import org.icij.datashare.extract.RedisBlockingQueue;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import sun.misc.Signal;

import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.*;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchAppTestInt {
    BlockingQueue<String> batchSearchQueue = new LinkedBlockingQueue<>();
    @Mock BatchSearchRunner batchSearchRunner;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

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
    public void test_batch_search_runner_called_when_app_started() throws Exception {
        when(batchSearchRunner.run("batchSearch.uuid")).thenReturn(12);
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);
        batchSearchQueue.add(BatchSearchApp.POISON);

        app.run();

        verify(batchSearchRunner).call();
    }

    @Test
    public void test_main_loop_exit_with_sigterm_when_empty_batch() throws InterruptedException {
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);

        executor.submit(app::run);
        Signal term = new Signal("TERM");
        Signal.raise(term);
        executor.shutdown();

        assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void test_main_loop_exit_with_sigterm_when_running_batch() throws InterruptedException {
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100);
        BatchSearchApp app = new BatchSearchApp(batchSearchRunner, batchSearchQueue);
        batchSearchQueue.add("batchSearch.uuid");
        executor.submit(app::run);
        waitQueueToBeEmpty();

        Signal term = new Signal("TERM");

        Signal.raise(term);
        executor.shutdown();

        assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
        assertThat(batchSearchRunner.cancelledCalled).isTrue();
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

    @After
    public void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    public void waitQueueToBeEmpty() throws InterruptedException {
        while (! batchSearchQueue.isEmpty()) {
            Thread.sleep(100);
        }
    }

    private static class SleepingBatchSearchRunner extends BatchSearchRunner {
        private final int sleepingMilliseconds;
        private volatile boolean cancelledCalled;

        public SleepingBatchSearchRunner(int sleepingMilliseconds) {
            super(mock(Indexer.class), mock(BatchSearchRepository.class), new PropertiesProvider(), User.local());
            this.sleepingMilliseconds = sleepingMilliseconds;
        }

        @Override
        public int run(String batchSearchId) {
            while(!cancelledCalled) {
                try {
                    Thread.sleep(sleepingMilliseconds);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            return 0;
        }

        @Override
        public void cancel() {
            this.cancelledCalled = true;
        }
    }
}
