package org.icij.datashare.tasks;

import static java.util.Optional.ofNullable;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractBatchNlpTask extends DefaultTask<Long> implements UserTask, CancellableTask {
    private static final List<String> EXCLUDED_SOURCES = List.of("contentTranslated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final User user;
    private final Function<Double, Void> progress;
    private volatile Thread taskThread;
    private final Indexer indexer;
    private final List<CreateNlpBatchesFromIndexWithHandlerTask.BatchDocument> docs;
    private final Pipeline pipeline;
    private final int maxLength;

    AbstractBatchNlpTask(
        Indexer indexer,
        PipelineRegistry registry,
        Task<Long> task,
        final Function<Double, Void> progress
    )
        throws IOException {
        this.user = task.getUser();
        registry.get(Pipeline.Type.parse((String) task.args.get("pipeline")));
        this.indexer = indexer;
        this.pipeline = registry.get(Pipeline.Type.parse((String) task.args.get("pipeline")));
        this.maxLength = Integer.parseInt((String) ofNullable(task.args.get("maxLength"))
            .orElseThrow(()-> new NullPointerException("missing maxLength args")));
        this.progress = progress;
        this.docs = fetchDocFromTask(task);
    }

    abstract List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument> fetchDocFromTask(Task<Long> tasks) throws IOException;

    @Override
    public Long call() throws IOException, InterruptedException {
        taskThread = Thread.currentThread();
        if (this.docs.isEmpty()) {
            return 0L;
        }
        int batchSize = this.docs.size();
        int updateRate = Integer.max(batchSize / 10, 1);
        Language language = this.docs.get(0).language();
        pipeline.initialize(language);
        logger.info("performing NER on {} docs in {}...", batchSize, language);
        // TODO: for now None of the Java NER seems to support batch processing, we just iterate docs one by one
        // TODO: we could improve perfs by fetching docs and processing them concurrently...
        int nProcessed = 0;
        Optional.ofNullable(this.progress).ifPresent(p -> p.apply(0.0));
        for (CreateNlpBatchesFromIndexWithHandlerTask.BatchDocument doc : this.docs) {
            String project = doc.project();
            Document indexDoc = indexer.get(project, doc.id(), doc.rootDocument(), EXCLUDED_SOURCES);
            if (indexDoc.getContentTextLength() < this.maxLength) {
                List<NamedEntity> namedEntities = pipeline.process(indexDoc);
                indexer.bulkAdd(project, pipeline.getType(), namedEntities, indexDoc);
            } else {
                int nbChunks = indexDoc.getContentTextLength() / this.maxLength + 1;
                for (int chunkIndex = 0; chunkIndex < nbChunks; chunkIndex++) {
                    List<NamedEntity> namedEntities =
                        pipeline.process(indexDoc, maxLength, chunkIndex * maxLength);
                    if (chunkIndex < nbChunks - 1) {
                        indexer.bulkAdd(project, namedEntities);
                    } else {
                        indexer.bulkAdd(project, pipeline.getType(), namedEntities, indexDoc);
                    }
                }
            }
            nProcessed += 1;
            if (nProcessed % updateRate == 0) {
                Double prog = (double) nProcessed / (double) batchSize;
                Optional.ofNullable(this.progress).ifPresent(p -> p.apply(prog));
            }
        }
        pipeline.terminate(language);
        Optional.ofNullable(this.progress).ifPresent(p -> p.apply(1.0));
        return (long) batchSize;
    }

    @Override
    public void cancel(boolean requeue) {
        ofNullable(taskThread).ifPresent(Thread::interrupt);
    }

    @Override
    public User getUser() {
        return user;
    }
}
