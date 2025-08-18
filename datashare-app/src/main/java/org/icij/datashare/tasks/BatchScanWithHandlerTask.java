package org.icij.datashare.tasks;

import static org.icij.datashare.LambdaExceptionUtils.rethrowConsumer;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Function;
import org.icij.datashare.asynctasks.ConductorTask;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ConductorTask(name = "BatchScanWithHandlerTask")
@TaskGroup(TaskGroupType.Java)
public class BatchScanWithHandlerTask extends AbstractBatchScanTask<ArrayList<String>> {
    private static final Logger logger = LoggerFactory.getLogger(BatchScanWithHandlerTask.class);
    private final BatchHandler<String> batchHandler;

    @Inject
    public BatchScanWithHandlerTask(
        BatchHandler<String> batchHandler, @Assisted Task<ArrayList<String>> task,
        @Assisted Function<Double, Void> ignored) {
        super(task);
        this.batchHandler = batchHandler;
    }

    @Override
    public ArrayList<String> call() throws IOException {
        int initialSize = batchHandler.size(task.id);
        visitor.consumeBatches(rethrowConsumer(b -> batchHandler.addBatch(task.getId(), b)));
        logger.info("Wrote {} batches !", batchHandler.size(task.id) - initialSize);
        return new ArrayList<>(batchHandler.keys(task.id).toList());
    }
}
