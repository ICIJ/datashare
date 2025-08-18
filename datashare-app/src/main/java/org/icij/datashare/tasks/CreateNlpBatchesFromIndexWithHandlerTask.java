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

@ConductorTask(name = "CreateNlpBatchesFromIndexWithHandlerTask")
@TaskGroup(TaskGroupType.Java)
public class CreateNlpBatchesFromIndexWithHandlerTask extends AbstractCreateNlpBatchesFromIndexTask<String> {
    private final BatchHandler<String> batchHandler;

    @Inject
    public CreateNlpBatchesFromIndexWithHandlerTask(
        final Indexer indexer,
        final BatchHandler<String> batchHandler,
        @Assisted final Task<ArrayList<String>> taskView,
        @Assisted final Function<Double, Void> ignored
    ) {
        super(indexer, taskView);
        this.batchHandler = batchHandler;
    }


    protected ThrowingFunction<List<BatchDocument>, String> getCreateNlpBatchFunction() {
        return b -> batchHandler.addBatch(task.id, b);
    }
}
