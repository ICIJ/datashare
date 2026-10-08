package org.icij.datashare.tasks;

import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** Wraps a real indexer so a test can check how a task configured the searchers it scrolled with. */
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
}
