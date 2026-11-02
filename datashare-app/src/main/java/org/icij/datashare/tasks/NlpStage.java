package org.icij.datashare.tasks;

import java.io.IOException;
import java.util.List;
import static java.util.Objects.requireNonNullElse;
import static java.util.Optional.ofNullable;
import org.icij.datashare.HumanReadableSize;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageResult;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_MAX_CONTENT_LENGTH;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_CONTENT_LENGTH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import static org.icij.extract.document.Identifier.shorten;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NlpStage implements StageProcessor<String, Void> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final PipelineRegistry pipelineRegistry;
    private final Pipeline pipeline;

    public NlpStage(Indexer indexer, PipelineRegistry registry) {
        this(indexer, registry, null);
    }

    /**
     * for delegation only. Pipeline should be set by ctx in process function
     *
     * @param indexer
     * @param pipeline
     */
    public NlpStage(Indexer indexer, Pipeline pipeline) {
        this(indexer, null, pipeline);
    }

    private NlpStage(Indexer indexer, PipelineRegistry pipelineRegistry, Pipeline pipeline) {
        this.indexer = indexer;
        requireNonNullElse(pipeline, pipelineRegistry);
        this.pipeline = pipeline;
        this.pipelineRegistry = pipelineRegistry;
    }

    @Override
    public StageResult process(List<String> docRefs, StageContext<Void> ctx) {
        Pipeline nlpPipeline = getNlpPipeline(ctx);
        int maxContentLengthChars = (int) HumanReadableSize.parse(
                ofNullable((String) ctx.options().get(MAX_CONTENT_LENGTH_OPT)).orElse(DEFAULT_MAX_CONTENT_LENGTH));
        Project project = Project.project(
                ofNullable((String) ctx.options().get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));
        int nbProcessedDocs = 0;
        for (String docRef : docRefs) {
            try {
                Document doc = indexer.get(project.getName(), DocReference.parse(docRef));
                if (doc != null) {
                    logger.info("extracting {} entities for document {}", nlpPipeline.getType(),
                                shorten(doc.getId(), 4));
                    if (nlpPipeline.initialize(doc.getLanguage())) {
                        int nbEntities = 0;
                        if (doc.getContent().length() < maxContentLengthChars) {
                            List<NamedEntity> namedEntities = nlpPipeline.process(doc);
                            indexer.bulkAdd(project.getName(), nlpPipeline.getType(), namedEntities, doc);
                        } else {
                            int nbChunks = doc.getContent().length() / maxContentLengthChars + 1;
                            logger.info("document is too large, extracting entities for {} document chunks", nbChunks);
                            for (int chunkIndex = 0; chunkIndex < nbChunks; chunkIndex++) {
                                List<NamedEntity> namedEntities = nlpPipeline.process(doc, maxContentLengthChars,
                                                                                      chunkIndex *
                                                                                      maxContentLengthChars);
                                if (chunkIndex < nbChunks - 1) {
                                    indexer.bulkAdd(project.getName(), namedEntities);
                                } else {
                                    indexer.bulkAdd(project.getName(), nlpPipeline.getType(), namedEntities, doc);
                                }
                            }
                        }
                        logger.info("added {} named entities to document {}", nbEntities, shorten(doc.getId(), 4));
                        nbProcessedDocs += 1;
                        nlpPipeline.terminate(doc.getLanguage());
                    }
                }
            } catch (InterruptedException e) {
                return new StageResult(nbProcessedDocs, 0, docRefs.size() - nbProcessedDocs - 1, 1);
            } catch (IOException e) {
                logger.error("cannot extract entities of doc {}", docRef, e);
            }
        }
        return new StageResult(nbProcessedDocs, 0, 0, 0);
    }

    private Pipeline getNlpPipeline(StageContext<Void> ctx) {
        return ofNullable(pipeline).orElseGet(
                () -> pipelineRegistry.get(Pipeline.Type.parse((String) ctx.options().get(NLP_PIPELINE_OPT))));
    }

    @Override
    public Class<String> itemType() {
        return String.class;
    }

    @Override
    public Stage stage() {
        return Stage.NLP;
    }
}
