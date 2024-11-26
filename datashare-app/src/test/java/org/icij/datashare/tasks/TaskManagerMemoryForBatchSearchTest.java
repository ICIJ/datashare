package org.icij.datashare.tasks;

import org.icij.datashare.CollectionUtils;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
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
import java.util.Date;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;


public class TaskManagerMemoryForBatchSearchTest {
    @Mock BatchSearchRunner batchSearchRunner;
    @Mock Indexer indexer;
    @Mock DatashareTaskFactory factory;
    @Mock BatchSearchRepository repository;

    CountDownLatch startLoop = new CountDownLatch(1);
    MockSearch<Indexer.QueryBuilderSearcher> mockSearch;
    TaskManagerMemory taskManager;
    BatchSearch testBatchSearch = new BatchSearch(singletonList(project("test-datashare")), "name", "desc", CollectionUtils.asSet("query") , local(), true, new LinkedList<>(), "queryBody", null, 0);

    @Test
    public void test_main_loop() throws Exception {
        mockSearch.willReturn(1, createDoc("doc1").build(), createDoc("doc2").build());
        taskManager.startTask(testBatchSearch.uuid, BatchSearchRunner.class, local());
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        verify(repository).setState(testBatchSearch.uuid, BatchSearch.State.RUNNING);
        verify(repository).setState(testBatchSearch.uuid, BatchSearch.State.SUCCESS);
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_when_empty_batch_queue() throws InterruptedException {
        startLoop.await();

        Signal term = new Signal("TERM");
        Signal.raise(term);

        assertThat(taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_and_wait_for_cancellation_to_terminate() throws Exception {
        DatashareTime.setMockTime(true);
        Date beforeTest = DatashareTime.getInstance().now();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100, countDownLatch, testBatchSearch);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        taskManager.startTask(testBatchSearch.uuid, BatchSearchRunner.class, local());

        countDownLatch.await();
        Signal.raise(new Signal("TERM"));
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        assertThat(DatashareTime.getInstance().now().getTime() - beforeTest.getTime()).isEqualTo(100);
        assertThat(taskManager.getTasks()).hasSize(1);
        assertThat(taskManager.getTasks().get(0).id).isEqualTo(testBatchSearch.uuid);
    }

    @Test(timeout = 2000)
    public void test_run_batch_search_failure() throws Exception {
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        mockSearch.willThrow(new IOException("io exception"));

        taskManager.startTask(new Task<>(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local(), new Group("TestGroup")));
        taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS);

        verify(repository).setState(testBatchSearch.uuid, BatchSearch.State.RUNNING);
        verify(repository).setState(eq(testBatchSearch.uuid), any(SearchException.class));
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_and_queued_batches() throws Exception {
        BatchSearch bs1 = new BatchSearch(singletonList(project("prj")), "name1", "desc", CollectionUtils.asSet("query1") , local());
        BatchSearch bs2 = new BatchSearch(singletonList(project("prj")), "name2", "desc", CollectionUtils.asSet("query2") , local());
        CountDownLatch bs1Started = new CountDownLatch(1);
        CountDownLatch bs2Started = new CountDownLatch(1);
        SleepingBatchSearchRunner bsr1 = new SleepingBatchSearchRunner(100, bs1Started, bs1);
        SleepingBatchSearchRunner bsr2 = new SleepingBatchSearchRunner(100, bs2Started, bs2);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(bsr1, bsr2);
        when(repository.get(bs1.uuid)).thenReturn(bs1);
        when(repository.get(bs2.uuid)).thenReturn(bs2);
        String taskView1Id = taskManager.startTask(bs1.uuid, BatchSearchRunner.class, bs1.user);
        String taskView2Id = taskManager.startTask(bs2.uuid, BatchSearchRunner.class, bs2.user);

        bs1Started.await();
        Signal.raise(new Signal("TERM"));
        taskManager.waitTasksToBeDone(1, TimeUnit.SECONDS);

        assertThat(taskManager.getTasks()).hasSize(2);
    }

    @Test(timeout = 2000)
    public void test_main_loop_exit_with_sigterm_when_running_batch() throws Exception {
        CountDownLatch bsStarted = new CountDownLatch(1);
        SleepingBatchSearchRunner batchSearchRunner = new SleepingBatchSearchRunner(100, bsStarted, testBatchSearch );
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
        taskManager.startTask(testBatchSearch.uuid, BatchSearchRunner.class, local());

        bsStarted.await();
        Signal term = new Signal("TERM");
        Signal.raise(term);

        assertThat(taskManager.shutdownAndAwaitTermination(1, TimeUnit.SECONDS)).isTrue();
        assertThat(batchSearchRunner.cancelAsked).isTrue();
    }

    @Before
    public void setUp() throws IOException {
        initMocks(this);
        taskManager = new TaskManagerMemory(factory, new PropertiesProvider(), startLoop);
        mockSearch = new MockSearch<>(indexer, Indexer.QueryBuilderSearcher.class);

        Task<Object> taskView = new Task<>(testBatchSearch.uuid, BatchSearchRunner.class.getName(), local(), new Group("TestGroup"));
        batchSearchRunner = new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView, taskView.progress(taskManager::progress));
        when(repository.get(eq(local()), anyString())).thenReturn(testBatchSearch);
        when(factory.createBatchSearchRunner(any(), any())).thenReturn(batchSearchRunner);
    }

    @After
    public void tearDown() throws InterruptedException {
        DatashareTime.setMockTime(false);
    }

    private class SleepingBatchSearchRunner extends BatchSearchRunner {
        private final int sleepingMilliseconds;
        private final CountDownLatch countDownLatch;

        public SleepingBatchSearchRunner(int sleepingMilliseconds, CountDownLatch countDownLatch, BatchSearch bs) {
            super(mock(Indexer.class), new PropertiesProvider(), repository, new Task<>(bs.uuid, BatchSearchRunner.class.getName(), local(), new Group("TestGroup")), (b) -> null);
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
            throw new CancelException(requeueCancel);
        }
    }
}
