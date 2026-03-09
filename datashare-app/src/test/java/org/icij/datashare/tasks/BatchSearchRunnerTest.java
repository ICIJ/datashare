package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.CancelException;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_MAX_TIME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_THROTTLE_OPT;
import static org.icij.datashare.tasks.BatchSearchRunner.MAX_BATCH_RESULT_SIZE;
import static org.icij.datashare.tasks.BatchSearchRunner.MAX_SCROLL_SIZE;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchRunnerTest {
    @Mock Indexer indexer;
    MockSearch<Indexer.QueryBuilderSearcher> mockSearch;
    @Mock Function<Double, Void> progressCb;
    @Mock BatchSearchRepository repository;
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Test
    public void test_run_null_batch_search() throws Exception {
        BatchSearch search = new BatchSearch("uuid", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call().nbResults()).isEqualTo(0);
    }

    @Test
    public void test_run_batch_search() throws Exception {
        Document[] documents = {createDoc("doc1").build(), createDoc("doc2").build()};
        mockSearch.willReturn(1, documents);
        BatchSearch search = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, User.local());
        when(repository.get(local(), search.uuid)).thenReturn(search);

        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb).call().nbQueriesWithoutResults()).isEqualTo(2);

        verify(progressCb).apply( 1.0);
    }

    private Task<?> taskView(BatchSearch search) {
        return new Task<>(search.uuid, BatchSearchRunner.class.getName(), local());
    }

    @Test(expected = RuntimeException.class)
    public void test_run_batch_search_failure() throws Exception {
        Document[] documents = {createDoc("doc").build()};
        mockSearch.willReturn(1, documents);
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        when(repository.get(local(), batchSearch.uuid)).thenReturn(batchSearch);
        when(repository.saveResults(anyString(), any(), anyList(), anyBoolean())).thenThrow(new RuntimeException());

        new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(batchSearch), progressCb).call();
    }

    @Test
    public void test_run_batch_search_truncate_to_60k_max_results() throws Exception {
        Document[] documents = IntStream.range(0, MAX_SCROLL_SIZE).mapToObj(i -> createDoc("doc" + i).build()).toArray(Document[]::new);
        mockSearch.willReturn(MAX_BATCH_RESULT_SIZE/MAX_SCROLL_SIZE + 1, documents);
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name", "desc", asSet("query"), new Date(), BatchSearch.State.QUEUED, local());
        when(repository.get(local(), batchSearch.uuid)).thenReturn(batchSearch);

        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(batchSearch), progressCb).call().nbResults()).isLessThan(60000);
    }

    @Test
    public void test_run_batch_search_with_throttle() throws Exception {
        mockSearch.willReturn(1, createDoc("doc").build());
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        when(repository.get(local(), batchSearch.uuid)).thenReturn(batchSearch);
        Date beforeBatch  = timeRule.now;

        new BatchSearchRunner(indexer, new PropertiesProvider(new HashMap<>() {{
            put(BATCH_THROTTLE_OPT, "1000");
        }}), repository, taskView(batchSearch), progressCb).call();

        assertThat(timeRule.now().getTime() - beforeBatch.getTime()).isEqualTo(1000);
    }

    @Test
    public void test_run_batch_search_with_throttle_should_not_last_more_than_max_time() throws Exception {
        mockSearch.willReturn(5, createDoc("doc").build());
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1",
                asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        when(repository.get(local(), batchSearch.uuid)).thenReturn(batchSearch);
        Date beforeBatch  = timeRule.now;

        SearchException searchException = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, new PropertiesProvider(new HashMap<>() {{
            put(BATCH_THROTTLE_OPT, "1000");
            put(BATCH_SEARCH_MAX_TIME_OPT, "1");
        }}), repository, taskView(batchSearch), progressCb).call());

        assertThat(searchException.toString()).contains("Batch timed out after 1s");
        assertThat(timeRule.now().getTime() - beforeBatch.getTime()).isEqualTo(1000);
    }

    // To avoid race conditions, this test relies heavily on synchronization.
    // The goal is to avoid having the BatchSearchRunner execute itself before receiving the cancel request,
    // which happened sometimes on the CI.
    @Test
    public void test_cancel_current_batch_search() throws Exception {
        // Given
        Document[] documents = {createDoc("doc1").build(), createDoc("doc2").build()};
        mockSearch.willReturn(1, documents);
        CountDownLatch runnerStarted = new CountDownLatch(1);
        BatchSearch search = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, User.local());
        when(repository.get(local(), search.uuid)).thenReturn(search);

        // The following block will halt the execution of the BatchSearchRunner until it receives the cancel request
        CountDownLatch cancelRequestedInRunner = new CountDownLatch(1);
        doAnswer(inv -> {
            //This will block the execution of the runner
            cancelRequestedInRunner.await();
            return null;
        }).when(repository).setState(any(), eq(BatchSearchRecord.State.RUNNING));

        // WHEN
        BatchSearchRunner batchSearchRunner = new BatchSearchRunner(indexer, new PropertiesProvider(), repository, taskView(search), progressCb, runnerStarted);
        Future<BatchSearchRunnerResult> result = executor.submit(batchSearchRunner);
        runnerStarted.await();
        executor.shutdown();

        // BatchSearchRunner.cancel is blocking until the thread returns,
        // so it must be started in a new thread to avoid interlocking
        Thread cancelThread = new Thread(() -> batchSearchRunner.cancel(false));
        cancelThread.start();

        //Wait for the batchSearchRunner to receive the instruction that it must be canceled
        while (!batchSearchRunner.cancelAsked) { Thread.yield(); }

        //BatchSearchRunner received the instruction that it must be canceled : its execution can resume
        cancelRequestedInRunner.countDown();

        cancelThread.join();

        // THEN
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        assertThat(assertThrows(ExecutionException.class, result::get).getCause()).isInstanceOf(CancelException.class);
    }

    @Before
    public void setUp() {
        initMocks(this);
        mockSearch = new MockSearch<>(indexer, Indexer.QueryBuilderSearcher.class);
    }
}
