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
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Properties;

import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toList;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;

public class EnqueueFromIndexTask extends PipelineTask<String> {
    private final DocumentCollectionFactory<String> factory;
    Logger logger = LoggerFactory.getLogger(getClass());
    private final Pipeline.Type nlpPipeline;
    private final User user;
    private final String projectName;
    private final Indexer indexer;

    @Inject
    public EnqueueFromIndexTask(final DocumentCollectionFactory<String> factory, final Indexer indexer,
                                @Assisted final User user, @Assisted final Properties taskProperties) {
        super(Stage.ENQUEUEIDX, user, factory, new PropertiesProvider(taskProperties), String.class);
        this.factory = factory;
        this.indexer = indexer;
        this.nlpPipeline = Pipeline.Type.parse(taskProperties.getProperty(NLP_PIPELINE_OPT));
        this.user = user;
        this.projectName = ofNullable(taskProperties.getProperty("defaultProject")).orElse("local-datashare");
    }

    @Override
    public Long call() throws Exception {
        Indexer.Searcher searcher = indexer.search(singletonList(projectName), Document.class).without(nlpPipeline).withSource("rootDocument");
        logger.info("resuming NLP name finding for index {} and {} : {} documents found", projectName, nlpPipeline, searcher.totalHits());
        List<? extends Entity> docsToProcess = searcher.scroll().collect(toList());
        long totalHits = searcher.totalHits();

        try (DocumentQueue<String> outputQueue = factory.createQueue(propertiesProvider, getOutputQueueName(), String.class)) {
            do {
                docsToProcess.forEach(doc -> outputQueue.add(doc.getId()));
                docsToProcess = searcher.scroll().collect(toList());
            } while (!docsToProcess.isEmpty());
            logger.info("enqueued into {} {} files without {} pipeline tags", queue.getName(), totalHits, nlpPipeline);
            searcher.clearScroll();
        }
        queue.close();

        return totalHits;
    }

    @Override
    public User getUser() { return user;}
}
