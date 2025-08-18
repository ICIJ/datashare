package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;

@ConductorTask(name = "BatchScanTask")
@TaskGroup(TaskGroupType.Java)
public class BatchScanTask extends AbstractBatchScanTask<ArrayList<List<String>>> {

    @Inject
    public BatchScanTask(@Assisted Task<ArrayList<List<String>>> task, @Assisted Function<Double, Void> ignored) {
        super(task);
    }

    @Override
    public ArrayList<List<String>> call() throws IOException {
        ArrayList<List<String>> batches = new ArrayList<>();
        visitor.consumeBatches(batches::add);
        return batches;
    }
}
