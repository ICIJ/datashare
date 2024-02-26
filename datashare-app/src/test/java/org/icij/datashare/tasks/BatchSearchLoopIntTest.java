package org.icij.datashare.tasks;

import org.icij.datashare.CollectionUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.time.DatashareTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import sun.misc.Signal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.TaskRunnerLoop.POISON;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchLoopIntTest {
    MockSearch<Indexer.QueryBuilderSearcher> mockSearch;
    BlockingQueue<TaskView<?>> batchSearchQueue = new LinkedBlockingQueue<>();
    TaskManagerMemory supplier = new TaskManagerMemory(new PropertiesProvider(), batchSearchQueue);
    BatchSearch testBatchSearch = new BatchSearch(singletonList(project("test-datashare")), "name", "desc", CollectionUtils.asSet("query") , local(), true, new LinkedList<>(), "queryBody", null, 0);
    @Mock BatchSearchRunner batchSearchRunner;
    @Mock Indexer indexer;
    @Mock TaskFactory factory;
    @Mock BatchSearchRepository repository;
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Test
    public void test_main_loop() throws IOException {
        BatchSearchLoop app = new BatchSearchLoop(supplier, factory);
        supplier.startTask(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local());
        batchSearchQueue.add(TaskView.nullObject());
        mockSearch.willReturn(1, createDoc("doc1").build(), createDoc("doc2").build());

        app.call();

        verify(repository).setState(testBatchSearch.uuid, BatchSearch.State.RUNNING);
        verify(repository).setState(testBatchSearch.uuid, BatchSearch.State.SUCCESS);
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_when_empty_batch_queue() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        BatchSearchLoop app = new BatchSearchLoop(supplier, factory, countDownLatch);

        executor.submit(app);
        countDownLatch.await();
        Signal term = new Signal("TERM");
        Signal.raise(term);
        executor.shutdown();

        assertThat(executor.awaitTermination(1,TimeUnit.SECONDS)).isTrue();
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_and_wait_for_cancellation_to_terminate() throws InterruptedException {
        DatashareTime.setMockTime(true);
        Date beforeTest = DatashareTime.getInstance().now();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100, countDownLatch, testBatchSearch);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        batchSearchQueue.add(new TaskView<>(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local()));
        BatchSearchLoop app = new BatchSearchLoop(supplier, factory);

        executor.submit(app);
        countDownLatch.await();
        Signal.raise(new Signal("TERM"));
        executor.shutdown();

        assertThat(executor.awaitTermination(1,TimeUnit.SECONDS)).isTrue();
        assertThat(DatashareTime.getInstance().now().getTime() - beforeTest.getTime()).isEqualTo(100);
        assertThat(batchSearchQueue).hasSize(1);
        assertThat(batchSearchQueue.take().id).isEqualTo(testBatchSearch.uuid);
    }

    @Test(timeout = 200000)
    public void test_run_batch_search_failure() throws IOException {
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        mockSearch.willThrow(new IOException("io exception"));
        BatchSearchLoop app = new BatchSearchLoop(supplier, factory);
        batchSearchQueue.add(new TaskView<>(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local()));
        batchSearchQueue.add(POISON);

        app.call();

        verify(repository).setState(testBatchSearch.uuid, BatchSearch.State.RUNNING);
        verify(repository).setState(eq(testBatchSearch.uuid), any(SearchException.class));
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_and_queued_batches() throws InterruptedException {
        BatchSearch bs1 = new BatchSearch(singletonList(project("prj")), "name1", "desc", CollectionUtils.asSet("query1") , local());
        BatchSearch bs2 = new BatchSearch(singletonList(project("prj")), "name2", "desc", CollectionUtils.asSet("query2") , local());
        SleepingBatchSearchRunner bsr1 = new SleepingBatchSearchRunner(100, bs1);
        SleepingBatchSearchRunner bsr2 = new SleepingBatchSearchRunner(100, bs2);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(bsr1, bsr2);
        BatchSearchLoop app = new BatchSearchLoop(supplier, factory);
        TaskView<Object> taskView1 = new TaskView<>(bs1.uuid, BatchSearchRunner.class.getName(), local());
        batchSearchQueue.add(taskView1);
        TaskView<Object> taskView2 = new TaskView<>(bs2.uuid, BatchSearchRunner.class.getName(), local());
        batchSearchQueue.add(taskView2);
        when(repository.get(bs1.uuid)).thenReturn(bs1);
        when(repository.get(bs2.uuid)).thenReturn(bs2);

        executor.submit(app);
        waitQueueToHaveSize(1);
        Signal.raise(new Signal("TERM"));
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        assertThat(batchSearchQueue).excludes("poison");
        assertThat(batchSearchQueue).containsOnly(taskView1, taskView2);
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_when_running_batch() throws InterruptedException {
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100,testBatchSearch );
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        BatchSearchLoop app = new BatchSearchLoop(supplier, factory);
        batchSearchQueue.add(new TaskView<>(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local()));
        executor.submit(app);
        waitQueueToBeEmpty();

        Signal term = new Signal("TERM");
        Signal.raise(term);
        executor.shutdown();

        assertThat(executor.awaitTermination(2,TimeUnit.SECONDS)).isTrue();
        assertThat(batchSearchRunner.cancelAsked).isTrue();
    }

    @Before
    public void setUp() throws IOException {
        initMocks(this);
        mockSearch = new MockSearch<>(indexer, Indexer.QueryBuilderSearcher.class);

        batchSearchRunner = new BatchSearchRunner(indexer, new PropertiesProvider(), repository,
                new TaskView<>(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local()), supplier::progress );
        when(repository.get(eq(local()), anyString())).thenReturn(testBatchSearch);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
    }

    @After
    public void tearDown() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2, TimeUnit.SECONDS);
        DatashareTime.setMockTime(false);
    }

    public void waitQueueToBeEmpty() throws InterruptedException {
        waitQueueToHaveSize(0);
    }

    public void waitQueueToHaveSize(int size) throws InterruptedException {
        while (batchSearchQueue.size() != size) {
            Thread.sleep(100);
        }
    }

    private class SleepingBatchSearchRunner extends BatchSearchRunner {
        private final int sleepingMilliseconds;
        private final CountDownLatch countDownLatch;

        public SleepingBatchSearchRunner(int sleepingMilliseconds, BatchSearch bs) {
            this(sleepingMilliseconds, new CountDownLatch(1), bs);
        }

        public SleepingBatchSearchRunner(int sleepingMilliseconds, CountDownLatch countDownLatch, BatchSearch bs) {
            super(mock(Indexer.class), new PropertiesProvider(), repository, new TaskView<Object>(bs.uuid, BatchSearchRunner.class.getName(), local()), (a, b) -> null);
            this.sleepingMilliseconds = sleepingMilliseconds;
            this.countDownLatch = countDownLatch;
        }

        @Override
        public Integer call() {
            countDownLatch.countDown();
            callThread = Thread.currentThread();
            while(!cancelAsked) {
                try {
                    Thread.sleep(sleepingMilliseconds); // Make sure that we wait this before mocktime.sleep()
                    DatashareTime.getInstance().sleep(sleepingMilliseconds);
                } catch (InterruptedException e) {
                    // nothing we throw a cancel later
                }
            }
            throw new BatchSearchRunner.CancelException(taskView.id);
        }
    }
}
