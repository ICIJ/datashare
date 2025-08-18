package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.function.ThrowingFunction;
import org.icij.datashare.text.indexing.Indexer;

@ConductorTask(name = "CreateNlpBatchesFromIndexTask")
@TaskGroup(TaskGroupType.Java)
public class CreateNlpBatchesFromIndexTask
    extends AbstractCreateNlpBatchesFromIndexTask<List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument>> {

    @Inject
    public CreateNlpBatchesFromIndexTask(
        final Indexer indexer,
        @Assisted final Task<ArrayList<List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument>>> taskView,
        @Assisted final Function<Double, Void> ignored
    ) {
        super(indexer, taskView);
    }


    protected ThrowingFunction<List<BatchDocument>, List<AbstractCreateNlpBatchesFromIndexTask.BatchDocument>> getCreateNlpBatchFunction() {
        return ThrowingFunction.identity();
    }
}
