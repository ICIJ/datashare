package org.icij.datashare.tasks;

import static java.lang.Integer.parseInt;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_BATCH_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TaskGroup("Java")
public class BatchEnqueueFromIndexTask extends PipelineTask<List> {
    // TODO: this is a bit ugly, List is unparametrized
    public static final int DEFAULT_NLP_BATCH_SIZE = 1000;
    private final DocumentCollectionFactory<List> factory;
    private final String searchQuery;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type nlpPipeline;
    private final int batchSize;
    private final String projectName;
    private final Indexer indexer;
    private final String scrollDuration;
    private final int scrollSize;

    @Inject
    public BatchEnqueueFromIndexTask(final DocumentCollectionFactory<List> factory, final Indexer indexer,
                                     @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> ignored) {
        super(Stage.BATCHENQUEUEIDX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), List.class);
        this.factory = factory;
        this.indexer = indexer;
        this.nlpPipeline =
            Pipeline.Type.parse((String) taskView.args.getOrDefault(NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name()));
        this.batchSize = (int) taskView.args.getOrDefault(NLP_BATCH_SIZE_OPT, DEFAULT_NLP_BATCH_SIZE);
        this.projectName = (String) taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE)));
        this.searchQuery = propertiesProvider.get(SEARCH_QUERY_OPT).orElse(null);
    }

    @Override
    public Long call() throws Exception {
        super.call();
        Indexer.Searcher searcher;
        if (searchQuery == null) {
            searcher = indexer.search(singletonList(projectName), Document.class).without(nlpPipeline);
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery));
        }
        searcher = searcher.limit(scrollSize).sort("language", Indexer.Searcher.SortOrder.ASC).withSource(false);
        long totalHits = searcher.totalHits();
        logger.info(
            "pushing batches of {} docs ids for index {}, pipeline {} with {} scroll and size of {}",
            totalHits, projectName, nlpPipeline, scrollDuration, scrollSize
        );
        List<? extends Entity> docs = searcher.scroll(scrollDuration).toList();
        ArrayList<String> batch = new ArrayList<>(this.batchSize);
        try (DocumentQueue<List> outputQueue = factory.createQueue(getOutputQueueName(), List.class)) {
            do {
                for (int batchI = 0; batchI < docs.size(); batchI += batchSize) {
                    final List<? extends Entity> finalDocs = docs;
                    List<String> batchAddition = IntStream.range(batchI, Integer.min(batchI + batchSize, docs.size()))
                        .mapToObj(i -> finalDocs.get(i).getId()).toList();
                    batch.addAll(batchAddition);
                    if (batch.size() >= batchSize) {
                        outputQueue.add(batch);
                        batch.clear();
                    }
                }
                docs = searcher.scroll(scrollDuration).collect(toList());
            } while (docs.size() >= scrollSize);
            if (!batch.isEmpty()) {
                outputQueue.add(batch);
            }
            outputQueue.add(STRING_LIST_POISON);
            logger.info("queued batches for {} docs into {}", totalHits, outputQueue.getName());
            searcher.clearScroll();
        }
        return totalHits;
    }
}
