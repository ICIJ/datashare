package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.function.Function;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.SearchQuery;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static java.lang.Integer.parseInt;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_DURATION;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_SCROLL_SIZE;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_DURATION_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SCROLL_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.SEARCH_QUERY_OPT;

@TaskGroup(TaskGroupType.Java)
public class EnqueueFromIndexTask extends PipelineTask<String> {
    private final DocumentCollectionFactory<String> factory;
    private final String searchQuery;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type nlpPipeline;
    private final String projectName;
    private final Indexer indexer;
    private final String scrollDuration;
    private final int scrollSize;

    @Inject
    public EnqueueFromIndexTask(final DocumentCollectionFactory<String> factory, final Indexer indexer,
                                @Assisted Task taskView, @Assisted final Function<Double, Void> ignored) {
        super(Stage.ENQUEUEIDX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.factory = factory;
        this.indexer = indexer;
        this.nlpPipeline = Pipeline.Type.parse((String) taskView.args.getOrDefault(NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name()));
        this.projectName = (String)taskView.args.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE)));
        this.searchQuery = propertiesProvider.get(SEARCH_QUERY_OPT).orElse(null);
    }

    @Override
    public Long runTask() throws Exception {
        super.runTask();
        Indexer.Searcher searcher;
        if (searchQuery == null) {
            searcher = indexer.search(singletonList(projectName), Document.class)
                    .without(nlpPipeline).withSource("rootDocument").limit(scrollSize);
        } else {
            searcher = indexer.search(singletonList(projectName), Document.class, new SearchQuery(searchQuery))
                    .withoutSource("content", "contentTranslated").limit(scrollSize);
        }
        searcher.sort("language", Indexer.Searcher.SortOrder.ASC);
        logger.info("enqueuing doc ids finding for index {} and {} with {} scroll and size of {} : {} documents found", projectName, nlpPipeline,
                scrollDuration, scrollSize, searcher.totalHits());
        List<? extends Entity> docsToProcess = searcher.scroll(scrollDuration).collect(toList());
        long totalHits = searcher.totalHits();

        try (DocumentQueue<String> outputQueue = factory.createQueue(getOutputQueueName(), String.class)) {
            do {
                docsToProcess.forEach(doc -> outputQueue.add(doc.getId()));
                docsToProcess = searcher.scroll(scrollDuration).toList();
            } while (!docsToProcess.isEmpty());
            searcher.clearScroll();
        }
        logger.info("enqueued into {} {} files", outputQueue.getName(), totalHits);
        return totalHits;
    }
}
