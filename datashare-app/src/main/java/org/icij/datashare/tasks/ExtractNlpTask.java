package org.icij.datashare.tasks;

import static java.lang.String.valueOf;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.cli.DatashareCliOptions.BATCH_SIZE_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_CONTENT_LENGTH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import static org.icij.extract.document.Identifier.shorten;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Annotations;
import org.icij.datashare.text.nlp.NlpTag;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtractNlpTask extends PipelineTask<String> implements Monitorable {
    private static final int DEFAULT_MAX_LENGTH = 4096;
    private static final int DEFAULT_BATCH_SIZE = 256;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Pipeline nlpPipeline;
    private final Project project;
    private final int maxContentLength;
    private final int batchSize;

    record BatchItem(Document doc, String text, int offset) {
    }

    @Inject
    public ExtractNlpTask(Indexer indexer, PipelineRegistry registry, final DocumentCollectionFactory<String> factory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> updateCallback) {
        this(indexer, registry.get(Pipeline.Type.parse((String)taskView.args.get(NLP_PIPELINE_OPT))), factory, taskView, updateCallback);
    }


    ExtractNlpTask(Indexer indexer, Pipeline pipeline, final DocumentCollectionFactory<String> factory, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> ignored) {
        super(Stage.NLP, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.nlpPipeline = pipeline;
        project = Project.project(ofNullable((String)taskView.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));
        maxContentLength = (int) HumanReadableSize.parse(ofNullable((String)taskView.args.get(MAX_CONTENT_LENGTH_OPT)).orElse(valueOf(DEFAULT_MAX_LENGTH)));
        batchSize = (int) HumanReadableSize.parse(ofNullable((String)taskView.args.get(BATCH_SIZE_OPT)).orElse(valueOf(DEFAULT_BATCH_SIZE)));
        this.indexer = indexer;
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("extracting Named Entities with pipeline {} for {} from queue {}", nlpPipeline.getType(), project, inputQueue.getName());
        long nbMessages;
        if (this.nlpPipeline.getType().extractFromDoc()) {
            nbMessages = extractFromDocs();
        } else {
            nbMessages = extractFromTexts();
        }
        logger.info("exiting ExtractNlpTask loop after {} messages.", nbMessages);
        return nbMessages;
    }

    long extractFromTexts() throws InterruptedException {
        // NLP models are loaded/initialized by language, to avoid loading overhead, docs are
        // received grouped by language and sent batched to the pipeline to avoid model reload.
        long nDocs = 0;
        String docId;
        Language currentLanguage = null;
        boolean languageInitialized = false;
        ArrayList<BatchItem> batch = new ArrayList<>(batchSize);
        while (!(STRING_POISON.equals(docId = inputQueue.poll(60, TimeUnit.SECONDS)))) {
            Document doc = indexer.get(project.getName(), docId);
            nDocs++;
            if (doc != null) {
                String docContent = doc.getContent();
                if (!doc.getLanguage().equals(currentLanguage)) {
                    if (!batch.isEmpty()) {
                        consumeBatch(batch, currentLanguage);
                    }
                    if (currentLanguage != null) {
                        nlpPipeline.terminate(currentLanguage);
                    }
                    currentLanguage = doc.getLanguage();
                    languageInitialized = nlpPipeline.initialize(currentLanguage);
                }
                if (!languageInitialized) {
                    continue;
                }
                int docLength = docContent.length();
                for (int begin = 0; begin < docLength; begin += maxContentLength) {
                    int end = Math.min(begin + maxContentLength, docLength);
                    String text = docContent.substring(begin, end);
                    batch.add(new BatchItem(doc, text, begin));
                    if (batch.size() >= batchSize) {
                        consumeBatch(batch, currentLanguage);
                    }
                }
            } else {
                logger.warn("no document found in index with id " + docId);
            }
        }
        if (!batch.isEmpty()) {
            consumeBatch(batch, currentLanguage);
        }
        if (currentLanguage != null) {
            nlpPipeline.terminate(currentLanguage);
        }
        return nDocs;
    }

    private void consumeBatch(List<BatchItem> batch, Language language) throws InterruptedException {
        List<List<NlpTag>> entities = nlpPipeline.processText(batch.stream().map(i -> i.text), language);
        Iterator<BatchItem> batchIt = batch.iterator();
        entities.forEach(chunkTags -> {
            BatchItem item = batchIt.next();
            Document doc = item.doc;
            Annotations annotations =
                new Annotations(doc.getId(), nlpPipeline.getType(), doc.getLanguage());
            int offset = item.offset;
            chunkTags.forEach(tag -> {
                int begin = tag.begin() + offset;
                int end = tag.end() + offset;
                annotations.add(begin, end, tag.category());
            });
            List<NamedEntity> chunkEntities = NamedEntity.allFrom(doc.getContent(), annotations);
            boolean isComplete = offset + item.text.length() == doc.getContentTextLength();
            try {
                if (isComplete) {
                    indexer.bulkAdd(project.getName(), nlpPipeline.getType(), chunkEntities, doc);
                } else {
                    indexer.bulkAdd(project.getName(), chunkEntities);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        batch.clear();
    }

    private long extractFromDocs() throws InterruptedException {
        String docId;
        long nbMessages = 0;
        while (!(STRING_POISON.equals(docId = inputQueue.poll(60, TimeUnit.SECONDS)))) {
            try {
                if (docId != null) {
                    findDocNamedEntities(project, docId);
                    nbMessages++;
                }
            } catch (Throwable e) {
                logger.error("error in ExtractNlpTask loop", e);
            }
        }
        return nbMessages;
    }

    void findDocNamedEntities(final Project project, final String id) throws InterruptedException {
        try {
            Document doc = indexer.get(project.getName(), id);
            if (doc != null) {
                logger.info("extracting {} entities for document {}", nlpPipeline.getType(), shorten(doc.getId(), 4));
                if (nlpPipeline.initialize(doc.getLanguage())) {
                    int nbEntities = 0;
                    if (doc.getContent().length() < this.maxContentLength) {
                        List<NamedEntity> namedEntities = nlpPipeline.processDoc(doc);
                        indexer.bulkAdd(project.getName(), nlpPipeline.getType(), namedEntities, doc);
                        nbEntities = namedEntities.size();
                    } else {
                        int nbChunks = doc.getContent().length() / this.maxContentLength + 1;
                        logger.info("document is too large, extracting entities for {} document chunks", nbChunks);
                        for (int chunkIndex = 0; chunkIndex < nbChunks; chunkIndex++) {
                            List<NamedEntity> namedEntities =
                                nlpPipeline.processDoc(doc, maxContentLength, chunkIndex * maxContentLength);
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
