package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskRepository;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static java.lang.String.valueOf;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.cli.DatashareCliOptions.MAX_CONTENT_LENGTH_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.NLP_PIPELINE_OPT;
import org.icij.datashare.asynctasks.TaskGroupType;
import static org.icij.extract.document.Identifier.shorten;

@TemporalSingleActivityWorkflow(name = "ner", activityOptions = @ActivityOpts(timeout = "P7D"))
@TaskGroup(TaskGroupType.Java)
public class ExtractNlpTask extends PipelineTask<String> implements Monitorable {
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 1024 * 1024;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Indexer indexer;
    private final Pipeline nlpPipeline;
    private final Project project;
    private final int maxContentLengthChars;
    private final Function<Double, Void> progressCallback;
    private final AtomicInteger processed = new AtomicInteger(0);
    private final TaskRepository taskRepository;

    @Inject
    public ExtractNlpTask(Indexer indexer, PipelineRegistry registry, final DocumentCollectionFactory<String> factory, final TaskRepository taskRepository, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        this(indexer, registry.get(Pipeline.Type.parse((String)taskView.args.get(NLP_PIPELINE_OPT))), factory, taskRepository, taskView, progressCallback);
    }


    ExtractNlpTask(Indexer indexer, Pipeline pipeline, final DocumentCollectionFactory<String> factory, final TaskRepository taskRepository, @Assisted Task<Long> taskView, @Assisted final Function<Double, Void> progressCallback) {
        super(Stage.NLP, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class);
        this.taskRepository = taskRepository;
        this.nlpPipeline = pipeline;
        project = Project.project(ofNullable((String)taskView.args.get(DEFAULT_PROJECT_OPT)).orElse(DEFAULT_DEFAULT_PROJECT));
        maxContentLengthChars = (int) HumanReadableSize.parse(ofNullable((String)taskView.args.get(MAX_CONTENT_LENGTH_OPT)).orElse(valueOf(DEFAULT_MAX_CONTENT_LENGTH)));
        this.indexer = indexer;
        this.progressCallback = progressCallback;
    }

    @Override
    public Long call() throws Exception {
        super.call();
        logger.info("extracting Named Entities with pipeline {} for {} from queue {}", nlpPipeline.getType(), project, inputQueue.getName());
        long nbMessages = 0;
        while (!Thread.currentThread().isInterrupted()) {
            String queueEntry;
            try {
                queueEntry = inputQueue.poll();
            } catch (RuntimeException e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                throw e;
            }
            if (queueEntry == null) {
                if (drained(taskRepository)) {
                    break;
                }
                Thread.sleep(UPSTREAM_POLL_INTERVAL_MS);
                continue;
            }
            if (isLegacySentinel(queueEntry)) {
                continue;
            }
            try {
                findNamedEntities(project, queueEntry);
                nbMessages++;
                processed.incrementAndGet();
                progressCallback.apply(getProgressRate());
            } catch (Throwable e) {
                if (causedByInterrupt(e)) {
                    Thread.currentThread().interrupt();
                    break;
                }
                logger.error("error in ExtractNlpTask loop on doc {}", queueEntry, e);
            }
        }
        // Thread.interrupted() tests AND clears: TaskWorkerLoop never clears the flag itself, so
        // leaving it set would leak the interrupt onto the runner thread and make the next task
        // start already cancelled.
        if (Thread.interrupted()) {
            throw new InterruptedException("cancelled while draining " + inputQueue.getName());
        }
        logger.info("exiting ExtractNlpTask loop after {} messages.", nbMessages);
        return nbMessages;
    }

    void findNamedEntities(final Project project, final String queueEntry) throws InterruptedException {
        try {
            Document doc = getDocument(indexer, project.getName(), DocReference.parse(queueEntry));
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
            }
        } catch (IOException e) {
            logger.error("cannot extract entities of doc {}", queueEntry, e);
        }
    }

    @Override
    public double getProgressRate() {
        int done = processed.get();
        int totalToProcess = done + inputQueue.size();
        return totalToProcess == 0 ? 0 : (double) done / totalToProcess;
    }
}
