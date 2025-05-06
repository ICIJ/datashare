package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;

import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.lang.String.valueOf;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_POLLING_INTERVAL_SEC;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_CONTENT_LENGTH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.POLLING_INTERVAL_SECONDS_OPT;
import org.icij.datashare.asynctasks.TaskGroupType;
import static org.icij.extract.document.Identifier.shorten;

@TaskGroup(TaskGroupType.Java)
public class ExtractNlpTask extends PipelineTask<String> implements Monitorable {
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
    public static final int NB_MAX_POLLS = 3;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Pipeline nlpPipeline;
    private final Project project;
    private final int maxContentLengthChars;
    private final float pollingIntervalSeconds;

    @Inject
    public ExtractNlpTask(Indexer indexer, PipelineRegistry registry, final DocumentCollectionFactory<String> factory, @Assisted Task taskView, @Assisted final Function<Double, Void> updateCallback) {
        this(indexer, registry.get(Pipeline.Type.parse((String)taskView.args.get(NLP_PIPELINE_OPT))), factory, taskView, updateCallback);
    }


    ExtractNlpTask(Indexer indexer, Pipeline pipeline, final DocumentCollectionFactory<String> factory, @Assisted Task taskView, @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.NLP, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.nlpPipeline = pipeline;
        project = Project.project(ofNullable((String)taskView.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));
        maxContentLengthChars = (int) HumanReadableSize.parse(ofNullable((String)taskView.args.get(MAX_CONTENT_LENGTH_OPT)).orElse(valueOf(DEFAULT_MAX_CONTENT_LENGTH)));
        pollingIntervalSeconds = Float.parseFloat(ofNullable((String)taskView.args.get(POLLING_INTERVAL_SECONDS_OPT)).orElse(DEFAULT_POLLING_INTERVAL_SEC));
        this.indexer = indexer;
    }

    @Override
    public Long runTask() throws Exception {
        super.runTask();
        logger.info("extracting Named Entities with pipeline {} for {} from queue {}", nlpPipeline.getType(), project, inputQueue.getName());
        String docId;
        long nbMessages = 0;
        int nbMaxPolls = NB_MAX_POLLS;
        while (!(STRING_POISON.equals(docId = inputQueue.poll((long) (pollingIntervalSeconds * 1000), TimeUnit.MILLISECONDS)))
                && nbMaxPolls > 0) {
            try {
                if (docId != null) {
                    findNamedEntities(project, docId);
                    nbMessages++;
                } else {
                    logger.info("will poll document queue again for pollingInterval={} seconds ({}/{})", pollingIntervalSeconds, nbMaxPolls, NB_MAX_POLLS);
                    nbMaxPolls--;
                }
            } catch (Throwable e) {
                logger.error("error in ExtractNlpTask loop", e);
            }
        }
        logger.info("exiting ExtractNlpTask loop after {} messages.", nbMessages);
        return nbMessages;
    }

    void findNamedEntities(final Project project, final String id) throws InterruptedException {
        try {
            Document doc = indexer.get(project.getName(), id);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), shorten(doc.getId(), 4));
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    int nbEntities = 0;
                    if (doc.getContent().length() < this.maxContentLengthChars) {
                        List<NamedEntity> namedEntities = nlpPipeline.process(doc);
                        indexer.bulkAdd(project.getName(), nlpPipeline.getType(), namedEntities, doc);
                        nbEntities = namedEntities.size();
                    } else {
                        int nbChunks = doc.getContent().length() / this.maxContentLengthChars + 1;
                        logger.info("document is too large, extracting entities for {} document chunks", nbChunks);
                        for (int chunkIndex = 0; chunkIndex < nbChunks; chunkIndex++) {
                            List<NamedEntity> namedEntities = nlpPipeline.process(doc, maxContentLengthChars, chunkIndex * maxContentLengthChars);
                            if (chunkIndex < nbChunks - 1) {
                                indexer.bulkAdd(project.getName(), namedEntities);
                            } else {
                                indexer.bulkAdd(project.getName(), nlpPipeline.getType(), namedEntities, doc);
                            }
                            nbEntities += namedEntities.size();
                        }
                    }
                    logger.info("added {} named entities to document {}", nbEntities, shorten(doc.getId(), 4));
                    nlpPipeline.terminate(doc.getLanguage());
                }
            } else {
                logger.warn("no document found in index with id {}", id);
            }
        } catch (IOException e) {
            logger.error("cannot extract entities of doc {}", id, e);
        }
    }

    @Override
    public double getProgressRate() {
        return 0;
    }
}
