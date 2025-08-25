package org.icij.datashare.tasks;

import static org.icij.datashare.json.JsonObjectMapper.MAPPER;

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

@ConductorTask(name = "BatchNlpWithSerializerTask")
@TaskGroup(TaskGroupType.Java)
public class BatchNlpWithSerializerTask extends AbstractBatchNlpTask {
    private final BatchSerializer<String> batchSerializer;

    @Inject
    public BatchNlpWithSerializerTask(
        BatchSerializer<String> batchSerializer,
        Indexer indexer,
        PipelineRegistry registry,
        @Assisted Task<Long> task,
        @Assisted final Function<Double, Void> progress
    ) {
        super(indexer, registry, task, progress);
        this.batchSerializer = batchSerializer;
    }

    @Override
    List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument> fetchDocFromTask(Task<Long> task) throws IOException {
        String batchId = (String) Optional.ofNullable(task.args.get("batchId"))
            .orElseThrow(() -> new NullPointerException("missing batchId arg"));
        return batchSerializer.getBatch(batchId).stream().map(e -> MAPPER.convertValue(e, AbstractCreateNlpBatchesFromIndexTask.BatchDocument.class)).toList();
    }
}
