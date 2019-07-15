package org.icij.datashare.tasks;

import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Stream;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.Language.FRENCH;
import static org.icij.datashare.text.Project.project;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;


public class BatchSearchRunnerTest {
    @Mock Indexer indexer;
    @Mock BatchSearchRepository repository;

    @Test
    public void test_run_batch_searches() throws Exception {
        Document[] documents = {createDoc("doc1"), createDoc("doc2")};
        firstSearchWillReturn(documents);
        when(repository.getQueued()).thenReturn(asList(
                new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asList("query1", "query2"), new Date(), BatchSearch.State.RUNNING),
                new BatchSearch("uuid2", project("test-datashare"), "name2", "desc1", asList("query3", "query4"), new Date(), BatchSearch.State.RUNNING)
        ));

        assertThat(new BatchSearchRunner(indexer, repository).call()).isEqualTo(2);

        verify(repository).saveResults("uuid1", "query1", asList(documents));
        verify(repository).setState("uuid1", BatchSearch.State.RUNNING);
        verify(repository).setState("uuid1", BatchSearch.State.SUCCESS);
        verify(repository, never()).saveResults(eq("uuid2"), anyString(), anyList());
    }

        @Test
    public void test_run_batch_search_failure() throws Exception {
        Document[] documents = {createDoc("doc")};
        firstSearchWillReturn(documents);
        when(repository.getQueued()).thenReturn(singletonList(
                new BatchSearch("uuid1", project("test-datashare"), "name1", "desc1", asList("query1", "query2"), new Date(), BatchSearch.State.RUNNING)
        ));
        when(repository.saveResults(anyString(), any(), anyList())).thenThrow(new RuntimeException());

        assertThat(new BatchSearchRunner(indexer, repository).call()).isEqualTo(1);

        verify(repository).setState("uuid1", BatchSearch.State.RUNNING);
        verify(repository).setState("uuid1", BatchSearch.State.FAILURE);
    }

    private void firstSearchWillReturn(Document... documents) throws IOException {
        Indexer.Searcher searcher = mock(Indexer.Searcher.class);
        when(searcher.scroll()).thenAnswer(a -> Stream.of(documents)).thenAnswer(a -> Stream.empty());
        when(searcher.with((String) any())).thenReturn(searcher);
        when(searcher.withoutSource(any())).thenReturn(searcher);
        when(searcher.totalHits()).thenReturn((long) documents.length).thenReturn(0L);
        when(indexer.search("test-datashare", Document.class)).thenReturn(searcher);
    }

    private Document createDoc(String name) {
             return new Document(project("prj"), "docid", Paths.get("/path/to/").resolve(name), name,
                     FRENCH, Charset.defaultCharset(),
                     "text/plain", new HashMap<>(), Document.Status.INDEXED,
                     new HashSet<>(), new Date(), null, null,
                     0, 123L);
         }

    @Before
    public void setUp() throws Exception { initMocks(this);}
}
