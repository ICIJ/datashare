package org.icij.datashare.batch;

import com.google.inject.Inject;
import org.icij.datashare.Entity;
import org.icij.datashare.batch.BatchSearch.State;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;

import static java.util.stream.Collectors.toList;

public class BatchSearchRunner implements Callable<Integer> {
    private Logger logger = LoggerFactory.getLogger(getClass());
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
        logger.info("found {} queued batch searches", batchSearches.size());
        int totalResults = 0;
        for (BatchSearch batchSearch : batchSearches) {
            totalResults += run(batchSearch);
        }
        logger.info("done {} batch searches", batchSearches.size());
        return totalResults;
    }

    private int run(BatchSearch batchSearch) throws SQLException {
        int results = 0;
        logger.info("running {} queries for batch search {} on project {}", batchSearch.queries.size(), batchSearch.uuid, batchSearch.project);
        repository.setState(batchSearch.uuid, State.RUNNING);
        try {
            for (String query : batchSearch.queries) {
                Indexer.Searcher searcher = indexer.search(batchSearch.project.getId(), Document.class).with(query).withoutSource("content");
                List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
                results += searcher.totalHits();

                while (docsToProcess.size() != 0) {
                    repository.saveResults(batchSearch.uuid, (List<Document>) docsToProcess);
                    docsToProcess = searcher.scroll().collect(toList());
                }
            }
        } catch (Exception ex) {
            logger.error("error when running batch " + batchSearch.uuid, ex);
            repository.setState(batchSearch.uuid, State.FAILURE);
            return results;
        }
        repository.setState(batchSearch.uuid, State.SUCCESS);
        logger.info("done batch search {} with success", batchSearch.uuid);
        return results;
    }
}
