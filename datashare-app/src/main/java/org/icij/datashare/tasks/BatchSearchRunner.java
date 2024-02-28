package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.client.ResponseException;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.SearchException;
import org.icij.datashare.function.TerFunction;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.ProjectProxy;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.time.DatashareTime;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.lang.String.valueOf;
import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.ProjectProxy.asCommaConcatNames;

public class BatchSearchRunner implements Callable<Integer>, Monitorable, UserTask {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * max scroll size will get n results at each scroll
     * each result is binding 9 fields on an insert query
     * and max sql binding is an int(2) = 32768
     * As we do batch insert with VALUES (val1, ..., val9), (val1, ..., val9)
     * max scroll size should be < 32768 / 9 (3640)
     */
    static final int MAX_SCROLL_SIZE = 3500;
    static final int MAX_BATCH_RESULT_SIZE = 60000;

    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final TerFunction<String, String, List<Document>, Boolean> resultConsumer;
    private final CountDownLatch callWaiterLatch;
    private int totalProcessed = 0;
    protected final BatchSearch batchSearch;
    protected volatile boolean cancelAsked = false;
    protected volatile Thread callThread;

    @Inject
    public BatchSearchRunner(Indexer indexer, PropertiesProvider propertiesProvider,
                             @Assisted BatchSearch batchSearch, @Assisted TerFunction<String, String, List<Document>, Boolean> resultConsumer) {
        this(indexer, propertiesProvider, batchSearch, resultConsumer, new CountDownLatch(1));
    }

    BatchSearchRunner(Indexer indexer, PropertiesProvider propertiesProvider,
                      BatchSearch batchSearch, TerFunction<String, String, List<Document>, Boolean> resultConsumer, CountDownLatch latch) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.batchSearch = batchSearch;
        this.resultConsumer = resultConsumer;
        this.callWaiterLatch = latch;
    }

    @Override
    public Integer call() throws SearchException {
        int numberOfResults = 0;
        int throttleMs = parseInt(propertiesProvider.get(BATCH_THROTTLE_OPT).orElse(DEFAULT_BATCH_THROTTLE));
        int maxTimeSeconds = parseInt(propertiesProvider.get(BATCH_SEARCH_MAX_TIME_OPT).orElse(DEFAULT_BATCH_SEARCH_MAX_TIME));
        int scrollSizeFromParams = parseInt(propertiesProvider.get(BATCH_SEARCH_SCROLL_SIZE_OPT)
                .orElse(propertiesProvider.get(SCROLL_SIZE_OPT)
                .orElse(valueOf(DEFAULT_SCROLL_SIZE))));
        int scrollSize = min(scrollSizeFromParams, MAX_SCROLL_SIZE);
        callThread = Thread.currentThread();
        callWaiterLatch.countDown(); // for tests
        logger.info("running {} queries for batch search {} on projects {} with throttle {}ms and scroll size of {}",
                batchSearch.queries.size(), batchSearch.uuid,  asCommaConcatNames(batchSearch.projects)
                , throttleMs, scrollSize);

        String query = null;
        try {
            for (String s : batchSearch.queries.keySet()) {
                query = s;
                Indexer.Searcher searcher;
                List<? extends Entity> docsToProcess;
                if (batchSearch.hasQueryTemplate()) { // for retro-compatibility should be removed at some point to keep only bodyTemplate
                    searcher = indexer.search(batchSearch.projects.stream().map(ProjectProxy::getId).collect(toList()), Document.class, batchSearch.queryTemplate)
                            .with(batchSearch.fuzziness, batchSearch.phraseMatches).withoutSource("content").limit(scrollSize);
                    docsToProcess = searcher.scroll(query).collect(toList());
                } else {
                    searcher = indexer.search(batchSearch.projects.stream().map(ProjectProxy::getId).collect(toList()), Document.class, new SearchQuery(query));
                    ((Indexer.QueryBuilderSearcher)searcher).withFieldValues("contentType", batchSearch.fileTypes.toArray(new String[]{}))
                            .withPrefixQuery("path", batchSearch.paths.toArray(new String[]{}))
                            .with(batchSearch.fuzziness, batchSearch.phraseMatches)
                            .withoutSource("content").limit(scrollSize);
                    docsToProcess = searcher.scroll().collect(toList());
                }

                long beforeScrollLoop = DatashareTime.getInstance().currentTimeMillis();
                while (docsToProcess.size() != 0 && numberOfResults < MAX_BATCH_RESULT_SIZE - MAX_SCROLL_SIZE) {
                    if (cancelAsked) {
                        throw new CancelException(batchSearch.uuid);
                    }
                    resultConsumer.apply(batchSearch.uuid, query, (List<Document>) docsToProcess);
                    if (DatashareTime.getInstance().currentTimeMillis() - beforeScrollLoop < maxTimeSeconds * 1000L) {
                        DatashareTime.getInstance().sleep(throttleMs);
                    } else {
                        throw new SearchException(query, new TimeoutException("Batch timed out after " + maxTimeSeconds + "s"));
                    }
                    numberOfResults += docsToProcess.size();
                    docsToProcess = searcher.scroll().collect(toList());
                }
                searcher.clearScroll();
                totalProcessed += 1;
            }
        } catch (ElasticsearchStatusException esEx) {
            throw new SearchException(query,
                    stream(esEx.getSuppressed()).filter(t -> t instanceof ResponseException).findFirst().orElse(esEx));
        } catch (IOException|InterruptedException ex) {
            throw new SearchException(query, ex);
        }
        logger.info("done batch search {} with success", batchSearch.uuid);
        return numberOfResults;
    }

    @Override
    public double getProgressRate() {
        return (double) totalProcessed / batchSearch.queries.size();
    }

    @Override
    public User getUser() {
        return batchSearch.user;
    }

    /**
     * cancel current batch search.
     * this method is blocking until batchsearch has exited
     */
    public void cancel() {
        cancelAsked = true;
        try {
            if (callThread != null) callThread.join();
        } catch (InterruptedException e) {
            logger.warn("batch search interrupted during cancel check status for {}", batchSearch.uuid);
        }
    }
    public static class CancelException extends RuntimeException {
        final String batchSearchId;
        public CancelException(String batchSearchId) {
            super(format("cancel %s", batchSearchId));
            this.batchSearchId = batchSearchId;
        }
    }
}
