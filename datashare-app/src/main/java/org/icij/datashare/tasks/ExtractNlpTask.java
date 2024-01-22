package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static java.lang.String.valueOf;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;

public class ExtractNlpTask extends PipelineTask<String> implements Monitorable {
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Pipeline nlpPipeline;
    private final Project project;
    private final int maxContentLengthChars;

    @Inject
    public ExtractNlpTask(Indexer indexer, PipelineRegistry registry, final DocumentCollectionFactory<String> factory, @Assisted User user, @Assisted String queueName, @Assisted final Properties properties) {
        this(indexer, registry.get(Pipeline.Type.parse(properties.getProperty(NLP_PIPELINE_OPT))), factory, user, queueName, properties);
    }

    ExtractNlpTask(Indexer indexer, Pipeline nlpPipeline, final DocumentCollectionFactory<String> factory, User user, String queueName, final Properties properties) {
        super(DatashareCli.Stage.NLP, user, queueName, factory, new PropertiesProvider(properties), String.class);
        this.nlpPipeline = nlpPipeline;
        project = Project.project(ofNullable(properties.getProperty("defaultProject")).orElse("local-datashare"));
        maxContentLengthChars = (int) HumanReadableSize.parse(ofNullable(properties.getProperty("maxContentLength")).orElse(valueOf(DEFAULT_MAX_CONTENT_LENGTH)));
        this.indexer = indexer;
    }

    @Override
    public Long call() throws InterruptedException {
        logger.info("extracting Named Entities with pipeline {} for {} from queue {}", nlpPipeline.getType(), project, queue.getName());
        String docId;
        long nbMessages = 0;
        while (!(STRING_POISON.equals(docId = queue.poll(60, TimeUnit.SECONDS)))) {
            try {
                if (docId !=  null) {
                    findNamedEntities(project, docId);
                    nbMessages++;
                }
            } catch (Throwable e) {
                logger.warn("error in ExtractNlpTask loop", e);
            }
        }
        logger.info("exiting ExtractNlpTask loop after {} messages", nbMessages);
        return nbMessages;
    }

    void findNamedEntities(final Project project, final String id) throws InterruptedException {
        try {
            Document doc = indexer.get(project.getName(), id);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), doc.getId());
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
                    logger.info("added {} named entities to document {}", nbEntities, doc.getId());
                    nlpPipeline.terminate(doc.getLanguage());
                }
            } else {
                logger.warn("no document found in index with id " + id);
            }
        } catch (IOException e) {
            logger.error("cannot extract entities of doc " + id, e);
        }
    }

    @Override
    public double getProgressRate() {
        return 0;
    }
}
