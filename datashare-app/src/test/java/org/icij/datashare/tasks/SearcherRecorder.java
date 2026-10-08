package org.icij.datashare.tasks;

import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class SearcherRecorder {
    final List<Indexer.Searcher> searchers = new ArrayList<>();

    ElasticsearchIndexer recording(ElasticsearchIndexer indexer) {
        ElasticsearchIndexer recordingIndexer = spy(indexer);
        doAnswer(invocation -> record(invocation.callRealMethod())).when(recordingIndexer).search(anyList(), any());
        doAnswer(invocation -> record(invocation.callRealMethod())).when(recordingIndexer)
                .search(anyList(), any(), any(SearchQuery.class));
        return recordingIndexer;
    }

    private Indexer.Searcher record(Object searcher) {
        Indexer.Searcher recordedSearcher = spy((Indexer.Searcher) searcher);
        searchers.add(recordedSearcher);
        return recordedSearcher;
    }

    // Doc values come back whether or not the source is loaded, so task output cannot tell the two apart:
    // only the searcher configuration shows a later source setting silently undoing withDocValues.
    void verifyReadsOnlyDocValues(String... fields) {
        Indexer.Searcher searcher = searchers.get(0);
        verify(searcher).withDocValues(fields);
        verify(searcher, never()).withSource(any(String[].class));
        verify(searcher, never()).withSource(anyBoolean());
        verify(searcher, never()).withoutSource(any(String[].class));
    }
}
