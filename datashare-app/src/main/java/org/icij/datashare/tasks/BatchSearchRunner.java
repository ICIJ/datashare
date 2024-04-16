package org.icij.datashare.tasks;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import jakarta.json.JsonException;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.batch.SearchException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;

import static java.lang.Integer.min;
import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_MAX_TIME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SEARCH_SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_THROTTLE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_SEARCH_MAX_TIME;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_BATCH_THROTTLE;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.text.ProjectProxy.asCommaConcatNames;

public class BatchSearchRunner implements CancellableCallable<Integer>, UserTask {
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
    private final BiFunction<String, Double, Void> updateCallback;

    private final CountDownLatch callWaiterLatch;
    private final BatchSearchRepository repository;
    protected final TaskView<String> taskView;
    protected volatile boolean cancelAsked = false;
    protected volatile Thread callThread;
    protected volatile boolean requeueCancel;

    @Inject
    public BatchSearchRunner(Indexer indexer, PropertiesProvider propertiesProvider, BatchSearchRepository repository,
                             @Assisted TaskView<?> taskView, @Assisted BiFunction<String, Double, Void> updateCallback) {
        this(indexer, propertiesProvider, repository, taskView, updateCallback, new CountDownLatch(1));
    }

    BatchSearchRunner(Indexer indexer, PropertiesProvider propertiesProvider, BatchSearchRepository repository,
                      TaskView<?> taskView, BiFunction<String, Double, Void> updateCallback, CountDownLatch latch) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.repository = repository;
        this.taskView = (TaskView<String>) taskView;
        this.updateCallback = updateCallback;
        this.callWaiterLatch = latch;
    }

    @Override
    public Integer call() throws SearchException {
        int numberOfResults = 0;
        int totalProcessed = 0;

        int throttleMs = parseInt(propertiesProvider.get(BATCH_THROTTLE_OPT).orElse(DEFAULT_BATCH_THROTTLE));
        int maxTimeSeconds = parseInt(propertiesProvider.get(BATCH_SEARCH_MAX_TIME_OPT).orElse(DEFAULT_BATCH_SEARCH_MAX_TIME));
        String scrollDuration = propertiesProvider.get(BATCH_SEARCH_SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        int scrollSizeFromParams = parseInt(propertiesProvider.get(BATCH_SEARCH_SCROLL_SIZE_OPT)
                .orElse(propertiesProvider.get(SCROLL_SIZE_OPT)
                .orElse(String.valueOf(DEFAULT_SCROLL_SIZE))));
        int scrollSize = min(scrollSizeFromParams, MAX_SCROLL_SIZE);
        callThread = Thread.currentThread();
        callWaiterLatch.countDown(); // for tests
        BatchSearch batchSearch = repository.get(taskView.getUser(), taskView.id);

        String query = null;
        try {
            logger.info("running {} queries for batch search {} on projects {} with throttle {}ms and scroll size of {}",
                    batchSearch.queries.size(), batchSearch.uuid, asCommaConcatNames(batchSearch.projects)
                    , throttleMs, scrollSize);
            repository.setState(batchSearch.uuid, BatchSearchRecord.State.RUNNING);
            for (String s : batchSearch.queries.keySet()) {
                query = s;
                Indexer.Searcher searcher;
                List<? extends Entity> docsToProcess;
                if (batchSearch.hasQueryTemplate()) { // for retro-compatibility should be removed at some point to keep only bodyTemplate
                    searcher = indexer.search(batchSearch.projects.stream().map(ProjectProxy::getId).collect(toList()), Document.class, batchSearch.queryTemplate)
                            .with(batchSearch.fuzziness, batchSearch.phraseMatches).withoutSource("content").limit(scrollSize);
                    docsToProcess = searcher.scroll(scrollDuration, query).collect(toList());
                } else {
                    searcher = indexer.search(batchSearch.projects.stream().map(ProjectProxy::getId).collect(toList()), Document.class, new SearchQuery(query));
                    ((Indexer.QueryBuilderSearcher) searcher).withFieldValues("contentType", batchSearch.fileTypes.toArray(new String[]{}))
                            .withPrefixQuery("path", batchSearch.paths.toArray(new String[]{}))
                            .with(batchSearch.fuzziness, batchSearch.phraseMatches)
                            .withoutSource("content").limit(scrollSize);
                    docsToProcess = searcher.scroll(scrollDuration).collect(toList());
                }

                long beforeScrollLoop = DatashareTime.getInstance().currentTimeMillis();
                while (docsToProcess.size() != 0 && numberOfResults < MAX_BATCH_RESULT_SIZE - MAX_SCROLL_SIZE) {
                    if (cancelAsked) {
                        logger.info("cancelling batch search {} requeue={}", batchSearch.uuid, requeueCancel);
                        repository.reset(batchSearch.uuid);
                        throw new CancelException(batchSearch.uuid, requeueCancel);
                    }
                    repository.saveResults(batchSearch.uuid, query, (List<Document>) docsToProcess);
                    if (DatashareTime.getInstance().currentTimeMillis() - beforeScrollLoop < maxTimeSeconds * 1000L) {
                        DatashareTime.getInstance().sleep(throttleMs);
                    } else {
                        throw new SearchException(query, new TimeoutException("Batch timed out after " + maxTimeSeconds + "s"));
                    }
                    numberOfResults += docsToProcess.size();
                    docsToProcess = searcher.scroll(scrollDuration).collect(toList());
                }
                searcher.clearScroll();
                totalProcessed += 1;
                updateCallback.apply(batchSearch.uuid, (double) totalProcessed / batchSearch.queries.size());
            }
            repository.setState(batchSearch.uuid, BatchSearchRecord.State.SUCCESS);
            logger.info("done batch search {} with success", batchSearch.uuid);
        } catch (ElasticsearchException esEx) {
            logger.error("ES exception while running batch " + taskView.id, esEx);
            repository.setState(taskView.id, new SearchException(query,
                    ElasticSearchAdapterException.createFrom(esEx)));
        } catch (IOException | InterruptedException | JsonException ex) {
            logger.error("exception while running batch " + taskView.id, ex);
            repository.setState(taskView.id, new SearchException(query, ex));
        }
        return numberOfResults;
    }

    @Override
    public User getUser() {
        return taskView.user;
    }

    /**
     * cancel current batch search.
     * this method is blocking until batchsearch has exited
     */
    public void cancel(String taskId, boolean requeue) {
        requeueCancel = requeue;
        cancelAsked = true;
        try {
            if (callThread != null) callThread.join();
        } catch (InterruptedException e) {
            logger.warn("batch search interrupted during cancel check status for {}", taskView.id);
        }
    }
}
