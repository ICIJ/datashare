package org.icij.datashare.tasks;

import org.icij.datashare.CollectionUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import sun.misc.Signal;

import java.util.Date;
import java.util.concurrent.*;

import static java.util.Arrays.asList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchLoopTestInt {
    BlockingQueue<String> batchSearchQueue = new LinkedBlockingQueue<>();
    BatchSearch batchSearch = new BatchSearch(Project.project("prj"), "name", "desc", CollectionUtils.asSet("query") , local());
    @Mock BatchSearchRunner batchSearchRunner;
    @Mock TaskFactory factory;
    @Mock BatchSearchRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Test
    public void test_main_loop() {
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);
        batchSearchQueue.add(batchSearch.uuid);
        app.enqueuePoison();

        app.run();

        verify(batchSearchRunner).call();
        verify(repository).setState(batchSearch.uuid, BatchSearch.State.RUNNING);
        verify(repository).setState(batchSearch.uuid, BatchSearch.State.SUCCESS);
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
    public void test_run_batch_search_failure() throws Exception {
        when(factory.createBatchSearchRunner(any(), any())).thenThrow(new SearchException("query", new RuntimeException()));
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);
        batchSearchQueue.add(batchSearch.uuid);
        app.enqueuePoison();

        app.run();

        verify(repository).setState(batchSearch.uuid, BatchSearch.State.RUNNING);
        verify(repository).setState(eq(batchSearch.uuid), any(SearchException.class));
    }


    @Test
    public void test_main_loop_exit_with_sigterm_when_running_batch() throws InterruptedException {
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        BatchSearchLoop app = new BatchSearchLoop(repository, batchSearchQueue, factory);
        batchSearchQueue.add(batchSearch.uuid);
        executor.submit(app::run);
        waitQueueToBeEmpty();

        Signal term = new Signal("TERM");
        Signal.raise(term);
        executor.shutdown();

        assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
        assertThat(batchSearchRunner.cancelledCalled).isTrue();
        verify(repository).reset(batchSearch.uuid);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(repository.get(anyString())).thenReturn(batchSearch);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
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
            super(mock(Indexer.class), new PropertiesProvider(), mock(BatchSearch.class), (a, b, c) -> true);
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
            throw new BatchSearchRunner.CancelException();
        }

        @Override
        public void cancel() {
            this.cancelledCalled = true;
        }
    }
}
