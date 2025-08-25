package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.text.indexing.Indexer;

@ConductorTask(name = "CreateNlpBatchesFromIndexWithSerializerTask")
@TaskGroup(TaskGroupType.Java)
public class CreateNlpBatchesFromIndexWithSerializerTask extends AbstractCreateNlpBatchesFromIndexTask<String> {
    private final BatchSerializer<String> batchSerializer;

    @Inject
    public CreateNlpBatchesFromIndexWithSerializerTask(
        final Indexer indexer,
        final BatchSerializer<String> batchSerializer,
        @Assisted final Task<ArrayList<String>> taskView,
        @Assisted final Function<Double, Void> ignored
    ) {
        super(indexer, taskView);
        this.batchSerializer = batchSerializer;
    }


    protected ThrowingFunction<List<BatchDocument>, String> getCreateNlpBatchFunction() {
        return b -> batchSerializer.addBatch(task.id, b);
    }
}
