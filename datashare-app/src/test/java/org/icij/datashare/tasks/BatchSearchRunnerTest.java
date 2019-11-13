package org.icij.datashare.tasks;

import org.icij.datashare.Entity;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.stubbing.OngoingStubbing;

import java.io.IOException;
import java.util.Date;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.CollectionUtils.asSet;
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

    @Test
    public void test_run_batch_searches() throws Exception {
        Document[] documents = {createDoc("doc1").build(), createDoc("doc2").build()};
        firstSearchWillReturn(1, documents);
        when(repository.getQueued()).thenReturn(asList(
                new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.RUNNING, User.local()),
                new BatchSearch("uuid2", project("test-datashare"), "name2", "desc1", asSet("query3", "query4"), new Date(), BatchSearch.State.RUNNING, User.local())
        ));

        assertThat(new BatchSearchRunner(indexer, repository, local()).call()).isEqualTo(2);

        verify(repository).saveResults("uuid1", "query1", asList(documents));
        verify(repository).setState("uuid1", BatchSearch.State.RUNNING);
        verify(repository).setState("uuid1", BatchSearch.State.SUCCESS);
        verify(repository, never()).saveResults(eq("uuid2"), anyString(), anyList());
    }

    @Test
    public void test_run_batch_search_failure() throws Exception {
        Document[] documents = {createDoc("doc").build()};
        firstSearchWillReturn(1, documents);
        when(repository.getQueued()).thenReturn(singletonList(
            new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asSet("query1", "query2"), new Date(), BatchSearch.State.RUNNING, User.local())
        ));
        when(repository.saveResults(anyString(), any(), anyList())).thenThrow(new RuntimeException());

        assertThat(new BatchSearchRunner(indexer, repository, local()).call()).isEqualTo(0);

        verify(repository).setState("uuid1", BatchSearch.State.RUNNING);
        verify(repository).setState(eq("uuid1"), any(SearchException.class));
    }

    @Test
    public void test_run_batch_search_truncate_to_60k_max_results() throws Exception {
        Document[] documents = IntStream.range(0, MAX_SCROLL_SIZE).mapToObj(i -> createDoc("doc" + i).build()).toArray(Document[]::new);
        firstSearchWillReturn(MAX_BATCH_RESULT_SIZE/MAX_SCROLL_SIZE + 1, documents);
        when(repository.getQueued()).thenReturn(singletonList(
            new BatchSearch("uuid1", project("test-datashare"), "name", "desc", asSet("query"), new Date(), BatchSearch.State.RUNNING, User.local())
        ));

        assertThat(new BatchSearchRunner(indexer, repository, local()).call()).isEqualTo(60000);
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




    @Before
    public void setUp() { initMocks(this);}
}
