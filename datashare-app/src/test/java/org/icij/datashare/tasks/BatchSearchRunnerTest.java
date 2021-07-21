package org.icij.datashare.tasks;

import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.test.DatashareTimeRule;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.stubbing.OngoingStubbing;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_MAX_TIME;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_THROTTLE;
import static org.icij.datashare.tasks.BatchSearchRunner.MAX_BATCH_RESULT_SIZE;
import static org.icij.datashare.tasks.BatchSearchRunner.MAX_SCROLL_SIZE;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;
import static org.icij.datashare.user.User.local;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchRunnerTest {
    @Mock Indexer indexer;
    @Mock BatchSearchRepository repository;
    @Rule public DatashareTimeRule timeRule = new DatashareTimeRule("2020-05-25T10:11:12Z");
    private final ExecutorService executor = Executors.newFixedThreadPool(1);

    @Test
    public void test_run_batch_search() throws Exception {
        Document[] documents = {createDoc("doc1").build(), createDoc("doc2").build()};
        firstSearchWillReturn(1, documents);
        BatchSearch search = new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, User.local());

        assertThat(new BatchSearchRunner(indexer, repository, new PropertiesProvider(), search).call()).isEqualTo(2);

        verify(repository).saveResults("uuid1", "query1", asList(documents));
        verify(repository).setState("uuid1", BatchSearch.State.RUNNING);
        verify(repository).setState("uuid1", BatchSearch.State.SUCCESS);
    }

    @Test
    public void test_run_batch_search_failure() throws Exception {
        Document[] documents = {createDoc("doc").build()};
        firstSearchWillReturn(1, documents);
        BatchSearch batchSearch = new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());

        when(repository.saveResults(anyString(), any(), anyList())).thenThrow(new RuntimeException());

        assertThat(new BatchSearchRunner(indexer, repository, new PropertiesProvider(), batchSearch).call()).isEqualTo(0);

        verify(repository).setState("uuid1", BatchSearch.State.RUNNING);
        verify(repository).setState(eq("uuid1"), any(SearchException.class));
    }

    @Test
    public void test_run_batch_search_truncate_to_60k_max_results() throws Exception {
        Document[] documents = IntStream.range(0, MAX_SCROLL_SIZE).mapToObj(i -> createDoc("doc" + i).build()).toArray(Document[]::new);
        firstSearchWillReturn(MAX_BATCH_RESULT_SIZE/MAX_SCROLL_SIZE + 1, documents);
        BatchSearch batchSearch = new BatchSearch("uuid1", project("test-datashare"), "name", "desc", asSet("query"), new Date(), BatchSearch.State.QUEUED, local());

        assertThat(new BatchSearchRunner(indexer, repository, new PropertiesProvider(), batchSearch).call()).isLessThan(60000);
    }

    @Test
    public void test_run_batch_search_with_throttle() throws Exception {
        firstSearchWillReturn(1, createDoc("doc").build());
        BatchSearch batchSearch = new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        Date beforeBatch  = timeRule.now;

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_SEARCH_THROTTLE, "1000");
        }}), batchSearch).call();

        assertThat(timeRule.now().getTime() - beforeBatch.getTime()).isEqualTo(1000);
    }

    @Test
    public void test_run_batch_search_with_throttle_should_not_last_more_than_max_time() throws Exception {
        firstSearchWillReturn(5, createDoc("doc").build());
        BatchSearch batchSearch = new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        Date beforeBatch  = timeRule.now;

        new BatchSearchRunner(indexer, repository, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_SEARCH_THROTTLE, "1000");
            put(BATCH_SEARCH_MAX_TIME, "1");
        }}), batchSearch).call();

        assertThat(timeRule.now().getTime() - beforeBatch.getTime()).isEqualTo(1000);
    }

    @Test
    public void test_cancel_current_batch_search() throws Exception {
        DatashareTime.setMockTime(false);
        BatchSearch batchSearch = new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.QUEUED, local());
        Document[] documents = {createDoc("doc").build()};
        firstSearchWillReturn(1,documents);
        BatchSearchRunner batchSearchRunner = new BatchSearchRunner(indexer, repository, new PropertiesProvider(new HashMap<String, String>() {{
            put(BATCH_SEARCH_THROTTLE, "10000");
        }}), batchSearch);

        executor.submit(batchSearchRunner);
        batchSearchRunner.cancel();
        executor.shutdown();

        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        verify(repository).reset("uuid1");
    }

    private void firstSearchWillReturn(int nbOfScrolls, Document... documents) throws IOException {
        Indexer.Searcher searcher = mock(Indexer.Searcher.class);
        OngoingStubbing<? extends Stream<? extends Entity>> ongoingStubbing = when(searcher.scroll());
        for (int i = 0 ; i<nbOfScrolls; i++) {
            ongoingStubbing = ongoingStubbing.thenAnswer(a -> Stream.of(documents));
        }
        ongoingStubbing.thenAnswer(a -> Stream.empty());
        when(searcher.with(any(),anyInt(),anyBoolean())).thenReturn(searcher);
        when(searcher.withoutSource(any())).thenReturn(searcher);
        when(searcher.withFieldValues(anyString())).thenReturn(searcher);
        when(searcher.withPrefixQuery(anyString())).thenReturn(searcher);
        when(searcher.limit(anyInt())).thenReturn(searcher);
        when(searcher.totalHits()).thenReturn((long) documents.length).thenReturn(0L);
        when(indexer.search("test-datashare", Document.class)).thenReturn(searcher);
    }

    private void returnBatchSearches(List<BatchSearch> ts) {
        when(repository.getQueued()).thenReturn(ts.stream().map(batchSearch -> batchSearch.uuid).collect(toList()));
        ts.forEach(bs -> when(repository.get(bs.uuid)).thenReturn(bs));
    }

    @Before
    public void setUp() { initMocks(this);}
}
