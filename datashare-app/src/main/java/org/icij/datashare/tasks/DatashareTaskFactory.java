package org.icij.datashare.tasks;

import java.util.function.Function;
import org.icij.datashare.asynctasks.TaskView;
import org.icij.datashare.user.User;


public interface DatashareTaskFactory extends org.icij.datashare.asynctasks.TaskFactory {
    BatchSearchRunner createBatchSearchRunner(TaskView<?> taskView, Function<Double, Void> updateCallback);
    BatchDownloadRunner createBatchDownloadRunner(TaskView<?> taskView, Function<Double, Void> updateCallback);

    ScanTask createScanTask(TaskView<Long> taskView, Function<Double, Void> updateCallback);
    IndexTask createIndexTask(TaskView<Long> taskView, Function<Double, Void> updateCallback);
    ScanIndexTask createScanIndexTask(TaskView<Long> taskView, Function<Double, Void> updateCallback);
    ExtractNlpTask createExtractNlpTask(TaskView<Long> taskView, Function<Double, Void> updateCallback);
    EnqueueFromIndexTask createEnqueueFromIndexTask(TaskView<Long> taskView, Function<Double, Void> updateCallback);
    DeduplicateTask createDeduplicateTask(TaskView<Long> taskView, Function<Double, Void> updateCallback);

    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);
}
