package org.icij.datashare.tasks;

import opennlp.tools.util.ObjectStream;
import org.icij.datashare.Entity;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.mockito.stubbing.OngoingStubbing;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MockSearch {
    private final Indexer mockIndexer;

    public MockSearch(Indexer mockIndexer) {
        this.mockIndexer = mockIndexer;
    }

    void willThrow(Exception expectedClassException) throws IOException {
        Indexer.Searcher searcher = mock(Indexer.Searcher.class);
        when(searcher.scroll()).thenThrow(expectedClassException);
        prepareSearcher(0, searcher);
    }

    void willReturn(int nbOfScrolls, Document... documents) throws IOException {
        Indexer.Searcher searcher = mock(Indexer.Searcher.class);
        OngoingStubbing<? extends Stream<? extends Entity>> ongoingStubbing = when(searcher.scroll());
        for (int i = 0 ; i<nbOfScrolls; i++) {
            ongoingStubbing = ongoingStubbing.thenAnswer(a -> Stream.of(documents));
        }
        ongoingStubbing.thenAnswer(a -> Stream.empty());
        prepareSearcher(documents.length, searcher);
    }

    private void prepareSearcher(long length, Indexer.Searcher searcher) {
        when(searcher.with(any(), anyInt(), anyBoolean())).thenReturn(searcher);
        when(searcher.withoutSource(any())).thenReturn(searcher);
        when(searcher.withFieldValues(anyString())).thenReturn(searcher);
        when(searcher.withPrefixQuery(anyString())).thenReturn(searcher);
        when(searcher.limit(anyInt())).thenReturn(searcher);
        when(searcher.totalHits()).thenReturn(length).thenReturn(0L);
        when(mockIndexer.search("test-datashare", Document.class)).thenReturn(searcher);
    }
}
