package org.icij.datashare.tasks;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.tasks.GroupHelper.JAVA_GROUP;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.List;
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
import org.icij.task.DefaultTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@TaskGroup(JAVA_GROUP)
public class BatchNlpTask extends DefaultTask<Long> implements UserTask, CancellableTask {
    // TODO: fix the raw used of parametrized type...
    private static final List<String> EXCLUDED_SOURCES = List.of("contentTranslated");
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final User user;
    private volatile Thread taskThread;
    private final Indexer indexer;
    private final List<BatchEnqueueFromIndexTask.BatchDocument> docs;
    private final Pipeline pipeline;
    private final int maxLength;

    @Inject
    public BatchNlpTask(Indexer indexer, PipelineRegistry registry, @Assisted Task<Long> taskView,
                        @Assisted final Function<Double, Void> updateCallback) {
        this(indexer, registry.get(Pipeline.Type.parse((String) taskView.args.get("pipeline"))), taskView, updateCallback);
    }


    BatchNlpTask(Indexer indexer, Pipeline pipeline, @Assisted Task<Long> taskView,
                 @Assisted final Function<Double, Void> ignored) {
        this.user = taskView.getUser();
        this.indexer = indexer;
        this.pipeline = pipeline;
        this.docs = (List<BatchEnqueueFromIndexTask.BatchDocument>) taskView.args.get("docs");
        this.maxLength = (int) taskView.args.get("maxLength");
    }

    @Override
    public Long call() throws Exception {
        taskThread = Thread.currentThread();
        if (this.docs.isEmpty()) {
            return 0L;
        }
        Language language = this.docs.get(0).language();
        pipeline.initialize(language);
        logger.info("performing NER on {} docs in {}...", this.docs.size(), language);
        // TODO: for now None of the Java NER seems to support batch processing, we just iterate docs one by one
        // TODO: we could improve perfs by fetching docs and processing them concurrently...
        for (BatchEnqueueFromIndexTask.BatchDocument doc : this.docs) {
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
        }
        pipeline.terminate(language);
        return (long) this.docs.size();
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
