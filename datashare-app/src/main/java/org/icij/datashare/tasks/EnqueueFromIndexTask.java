package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.Entity;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiFunction;

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

public class EnqueueFromIndexTask extends PipelineTask<String> {
    private final DocumentCollectionFactory<String> factory;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type nlpPipeline;
    private final String projectName;
    private final Indexer indexer;
    private final String scrollDuration;
    private final int scrollSize;

    @Inject
    public EnqueueFromIndexTask(final DocumentCollectionFactory<String> factory, final Indexer indexer,
                                @Assisted TaskView<Long> taskView, @Assisted final BiFunction<String, Double, Void> updateCallback) {
        super(Stage.ENQUEUEIDX, taskView.user, factory, new PropertiesProvider(taskView.properties), String.class);
        this.factory = factory;
        this.indexer = indexer;
        this.nlpPipeline = Pipeline.Type.parse((String) taskView.properties.getOrDefault(NLP_PIPELINE_OPT, Pipeline.Type.CORENLP.name()));
        this.projectName = (String)taskView.properties.getOrDefault(DEFAULT_PROJECT_OPT, DEFAULT_DEFAULT_PROJECT);
        this.scrollDuration = propertiesProvider.get(SCROLL_DURATION_OPT).orElse(DEFAULT_SCROLL_DURATION);
        this.scrollSize = parseInt(propertiesProvider.get(SCROLL_SIZE_OPT).orElse(String.valueOf(DEFAULT_SCROLL_SIZE)));

    }

    @Override
    public Long call() throws Exception {
        super.call();
        Indexer.Searcher searcher = indexer.search(singletonList(projectName), Document.class)
                .without(nlpPipeline).withSource("rootDocument").limit(scrollSize);
        logger.info("resuming NLP name finding for index {} and {} with {} scroll and size of {} : {} documents found", projectName, nlpPipeline,
                scrollDuration, scrollSize, searcher.totalHits());
        List<? extends Entity> docsToProcess = searcher.scroll(scrollDuration).collect(toList());
        long totalHits = searcher.totalHits();

        try (DocumentQueue<String> outputQueue = factory.createQueue(getOutputQueueName(), String.class)) {
            do {
                docsToProcess.forEach(doc -> outputQueue.add(doc.getId()));
                docsToProcess = searcher.scroll(scrollDuration).collect(toList());
            } while (!docsToProcess.isEmpty());
            outputQueue.add(STRING_POISON);
            logger.info("enqueued into {} {} files without {} pipeline tags", outputQueue.getName(), totalHits, nlpPipeline);
            searcher.clearScroll();
        }

        return totalHits;
    }
}
