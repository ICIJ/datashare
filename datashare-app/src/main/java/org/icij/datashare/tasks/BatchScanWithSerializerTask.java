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

@ConductorTask(name = "BatchScanWithSerializerTask")
@TaskGroup(TaskGroupType.Java)
public class BatchScanWithSerializerTask extends AbstractBatchScanTask<ArrayList<String>> {
    private static final Logger logger = LoggerFactory.getLogger(BatchScanWithSerializerTask.class);
    private final BatchSerializer<String> batchSerializer;

    @Inject
    public BatchScanWithSerializerTask(
        BatchSerializer<String> batchSerializer, @Assisted Task<ArrayList<String>> task,
        @Assisted Function<Double, Void> ignored) {
        super(task);
        this.batchSerializer = batchSerializer;
    }

    @Override
    public ArrayList<String> call() throws IOException {
        int initialSize = batchSerializer.size(task.id);
        visitor.consumeBatches(rethrowConsumer(b -> batchSerializer.addBatch(task.getId(), b)));
        logger.info("Wrote {} batches !", batchSerializer.size(task.id) - initialSize);
        return new ArrayList<>(batchSerializer.keys(task.id).toList());
    }
}
