package org.icij.datashare;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import sun.misc.Signal;

import java.util.concurrent.*;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.local;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchLoopTestInt {
    BlockingQueue<String> batchSearchQueue = new LinkedBlockingQueue<>();
    @Mock BatchSearchRunner batchSearchRunner;
    @Mock TaskFactory factory;
    @Mock BatchSearchRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Test
    public void test_main_loop() throws Exception {
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);
        batchSearchQueue.add("batchSearch.uuid");
        app.enqueuePoison();

        app.run();

        verify(batchSearchRunner).call();
    }

    @Test
    public void test_queued_batch_search_requeueing() throws Exception {
        when(repository.getQueued()).thenReturn(asList("uuid1", "uuid2"));
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);

        assertThat(app.requeueDatabaseBatches()).isEqualTo(2);
        app.enqueuePoison();
        app.run();

        verify(batchSearchRunner, times(2)).call();
    }

    @Test
    public void test_main_loop_exit_with_sigterm_when_empty_batch() throws InterruptedException {
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);

        executor.submit(app::run);
        Signal term = new Signal("TERM");
        Signal.raise(term);
        executor.shutdown();

        assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void test_main_loop_exit_with_sigterm_when_running_batch() throws InterruptedException {
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100);
        when(factory.createBatchSearchRunner(any())).thenReturn(batchSearchRunner);
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);
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
    public void test_main_loop_exception() throws Exception {
        when(batchSearchRunner.call()).thenThrow(new Exception("test runtime"));
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);
        batchSearchQueue.add("test");
        app.enqueuePoison();

        app.run();

        verify(batchSearchRunner).call();
    }

    @Before
    public void setUp() {
        initMocks(this);
        BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "desc",CollectionUtils.asSet("query") , local());
        when(repository.get(anyString())).thenReturn(batchSearch);
        when(factory.createBatchSearchRunner(any())).thenReturn(batchSearchRunner);
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
            super(mock(Indexer.class), mock(BatchSearchRepository.class), new PropertiesProvider(), mock(BatchSearch.class));
            this.sleepingMilliseconds = sleepingMilliseconds;
        }

        @Override
        public Integer call() {
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
