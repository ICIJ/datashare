package org.icij.datashare.tasks;

import org.icij.datashare.Entity;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.mockito.stubbing.OngoingStubbing;

import java.io.IOException;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockSearch<S extends Indexer.Searcher> {
    private final Indexer mockIndexer;
    private final Class<S> searcherInstance;

    public MockSearch(Indexer mockIndexer, Class<S> searcherInstance) {
        this.mockIndexer = mockIndexer;
        this.searcherInstance = searcherInstance;
    }

    void willThrow(Exception expectedClassException) throws IOException {
        Indexer.Searcher searcher = mock(Indexer.Searcher.class);
        when(searcher.scroll(any(String.class))).thenThrow(expectedClassException);
        when(searcher.scroll(any(String.class), any(String.class))).thenThrow(expectedClassException);
        prepareSearcher(0, searcher);
    }

    void willReturn(int nbOfScrolls, Document... documents) throws IOException {
        S searcher = mock(searcherInstance);
        OngoingStubbing<? extends Stream<? extends Entity>> ongoingStubbing = when(searcher.scroll(any(String.class)));
        for (int i = 0 ; i<nbOfScrolls; i++) {
            ongoingStubbing = ongoingStubbing.thenAnswer(a -> Stream.of(documents));
        }
        ongoingStubbing.thenAnswer(a -> Stream.empty());
        ongoingStubbing = when(searcher.scroll(anyString()));
        for (int i = 0 ; i<nbOfScrolls; i++) {
            ongoingStubbing = ongoingStubbing.thenAnswer(a -> Stream.of(documents));
        }
        ongoingStubbing.thenAnswer(a -> Stream.empty());
        prepareSearcher(documents.length, searcher);
    }

    private void prepareSearcher(long length, Indexer.Searcher searcher) {
        when(searcher.with(anyInt(), anyBoolean())).thenReturn(searcher);
        when(searcher.withoutSource(any())).thenReturn(searcher);
        if (searcher instanceof Indexer.QueryBuilderSearcher) {
            when(((Indexer.QueryBuilderSearcher)searcher).withFieldValues(anyString())).thenReturn((Indexer.QueryBuilderSearcher) searcher);
            when(((Indexer.QueryBuilderSearcher)searcher).withPrefixQuery(anyString())).thenReturn((Indexer.QueryBuilderSearcher) searcher);
        }
        when(searcher.limit(anyInt())).thenReturn(searcher);
        when(searcher.totalHits()).thenReturn(length).thenReturn(0L);
        when(mockIndexer.search(eq(singletonList("test-datashare")), eq(Document.class), any())).thenReturn(searcher);
    }
}
