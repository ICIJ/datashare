package org.icij.datashare.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.text.indexing.Indexer;

@ConductorTask(name = "BatchNlpWithHandlerTask")
@TaskGroup(TaskGroupType.Java)
public class BatchNlpWithHandlerTask extends AbstractBatchNlpTask {
    private static final TypeReference<List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument>> typeRef =
        new TypeReference<>() {
        };
    private final BatchHandler<String> batchHandler;

    @Inject
    public BatchNlpWithHandlerTask(
        BatchHandler<String> batchHandler,
        Indexer indexer,
        PipelineRegistry registry,
        @Assisted Task<Long> task,
        @Assisted final Function<Double, Void> progress
    ) throws IOException {
        super(indexer, registry, task, progress);
        this.batchHandler = batchHandler;
    }

    @Override
    List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument> fetchDocFromTask(Task<Long> task) throws IOException {
        String batchId = (String) Optional.ofNullable(task.args.get("batchId"))
            .orElseThrow(() -> new NullPointerException("missing batchId arg"));
        return (List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument>) batchHandler.getBatch(batchId);
    }
}
