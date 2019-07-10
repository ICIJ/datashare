package org.icij.datashare.batch;

import com.google.inject.Inject;
import org.icij.datashare.Entity;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;

import java.util.List;
import java.util.concurrent.Callable;

import static java.util.stream.Collectors.toList;

public class BatchSearchRunner implements Callable<Integer> {
    private final Indexer indexer;
    private final BatchSearchRepository repository;

    @Inject
    public BatchSearchRunner(Indexer indexer, BatchSearchRepository repository) {
        this.indexer = indexer;
        this.repository = repository;
    }

    @Override
    public Integer call() throws Exception {
        List<BatchSearch> batchSearches = repository.getQueued();
        int totalResults = 0;
        for (BatchSearch batchSearch : batchSearches) {
            for (String query: batchSearch.queries) {
                Indexer.Searcher searcher = indexer.search(batchSearch.project.getId(), Document.class).with(query);
                List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
                totalResults += searcher.totalHits();

                while (docsToProcess.size() != 0) {
                    repository.saveResults(batchSearch.uuid, (List<Document>) docsToProcess);
                    docsToProcess = searcher.scroll().collect(toList());
                }
            }
        }
        return totalResults;
    }
}
