package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.function.TerFunction;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_MAX_TIME;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_THROTTLE;
import static org.icij.datashare.tasks.BatchSearchRunner.MAX_BATCH_RESULT_SIZE;
import static org.icij.datashare.tasks.BatchSearchRunner.MAX_SCROLL_SIZE;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.junit.Assert.assertThrows;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchRunnerTest {
    @Mock Indexer indexer;
    MockSearch<Indexer.QueryBuilderSearcher> mockSearch;
    @Mock TerFunction<String, String, List<Document>, Boolean> resultConsumer;
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Test
    public void test_run_batch_search() throws Exception {
        Document[] documents = {createDoc("doc1").build(), createDoc("doc2").build()};
        mockSearch.willReturn(1, documents);
        BatchSearch search = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, User.local());

        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), search, resultConsumer).call()).isEqualTo(2);

        verify(resultConsumer).apply("uuid1", "query1", asList(documents));
    }

    @Test(expected = RuntimeException.class)
    public void test_run_batch_search_failure() throws Exception {
        Document[] documents = {createDoc("doc").build()};
        mockSearch.willReturn(1, documents);
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());

        when(resultConsumer.apply(anyString(), any(), anyList())).thenThrow(new RuntimeException());

        new BatchSearchRunner(indexer, new PropertiesProvider(), batchSearch, resultConsumer).call();
    }

    @Test
    public void test_run_batch_search_truncate_to_60k_max_results() throws Exception {
        Document[] documents = IntStream.range(0, MAX_SCROLL_SIZE).mapToObj(i -> createDoc("doc" + i).build()).toArray(Document[]::new);
        mockSearch.willReturn(MAX_BATCH_RESULT_SIZE/MAX_SCROLL_SIZE + 1, documents);
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name", "desc", asSet("query"), new Date(), BatchSearch.State.QUEUED, local());

        assertThat(new BatchSearchRunner(indexer, new PropertiesProvider(), batchSearch, resultConsumer).call()).isLessThan(60000);
    }

    @Test
    public void test_run_batch_search_with_throttle() throws Exception {
        mockSearch.willReturn(1, createDoc("doc").build());
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        Date beforeBatch  = timeRule.now;

        new BatchSearchRunner(indexer, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_THROTTLE, "1000");
        }}), batchSearch, resultConsumer).call();

        assertThat(timeRule.now().getTime() - beforeBatch.getTime()).isEqualTo(1000);
    }

    @Test
    public void test_run_batch_search_with_throttle_should_not_last_more_than_max_time() throws Exception {
        mockSearch.willReturn(5, createDoc("doc").build());
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1",
                asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        Date beforeBatch  = timeRule.now;

        SearchException searchException = assertThrows(SearchException.class, () -> new BatchSearchRunner(indexer, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_THROTTLE, "1000");
            put(BATCH_SEARCH_MAX_TIME, "1");
        }}), batchSearch, resultConsumer).call());

        assertThat(searchException.toString()).contains("Batch timed out after 1s");
        assertThat(timeRule.now().getTime() - beforeBatch.getTime()).isEqualTo(1000);
    }

    @Test
    public void test_cancel_current_batch_search() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        BatchSearch batchSearch = new BatchSearch("uuid1", singletonList(project("test-datashare")), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        Document[] documents = {createDoc("doc").build()};
        mockSearch.willReturn(1,documents);
        BatchSearchRunner batchSearchRunner = new BatchSearchRunner(indexer, new PropertiesProvider(), batchSearch, resultConsumer, countDownLatch);

        executor.submit(batchSearchRunner);
        executor.shutdown();
        countDownLatch.await();
        batchSearchRunner.cancel();

        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Before
    public void setUp() { initMocks(this); mockSearch = new MockSearch<>(indexer, Indexer.QueryBuilderSearcher.class);}
}
