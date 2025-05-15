package org.icij.datashare.tasks;

import static java.util.Optional.ofNullable;
import org.icij.datashare.asynctasks.TaskGroupType;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.icij.datashare.asynctasks.CancellableTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.NamedEntity;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.datashare.user.UserTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TaskGroup(TaskGroupType.Java)
public class BatchNlpTask extends DatashareTask implements UserTask, CancellableTask {
    private static final List<String> EXCLUDED_SOURCES = List.of("contentTranslated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final User user;
    private final Function<Double, Void> progress;
    private volatile Thread taskThread;
    private final Indexer indexer;
    private final List<CreateNlpBatchesFromIndex.BatchDocument> docs;
    private final Pipeline pipeline;
    private final int maxLength;

    @Inject
    public BatchNlpTask(Indexer indexer, PipelineRegistry registry, @Assisted Task taskView, @Assisted final Function<Double, Void> progress) {
        this(indexer, registry.get(Pipeline.Type.parse((String) taskView.args.get("pipeline"))), taskView, progress);
    }


    BatchNlpTask(Indexer indexer, Pipeline pipeline, Task taskView, final Function<Double, Void> progress) {
        this.user = taskView.getUser();
        this.indexer = indexer;
        this.pipeline = pipeline;
        this.docs = (List<CreateNlpBatchesFromIndex.BatchDocument>) taskView.args.get("docs");
        this.maxLength = (int) taskView.args.get("maxLength");
        this.progress = progress;
    }

    @Override
    public Long runTask() throws IOException, InterruptedException {
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
        for (CreateNlpBatchesFromIndex.BatchDocument doc : this.docs) {
            String project = doc.project();
            Document indexDoc = indexer.get(doc.id(), doc.rootDocument(), EXCLUDED_SOURCES);
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
