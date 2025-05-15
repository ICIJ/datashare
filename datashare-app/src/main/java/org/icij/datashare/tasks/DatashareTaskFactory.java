package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.user.User;

import java.util.function.Function;


public interface DatashareTaskFactory extends org.icij.datashare.asynctasks.TaskFactory {
    BatchSearchRunner createBatchSearchRunner(Task taskView, Function<Double, Void> updateCallback);
    BatchDownloadRunner createBatchDownloadRunner(Task taskView, Function<Double, Void> updateCallback);

    ScanTask createScanTask(Task taskView, Function<Double, Void> updateCallback);
    IndexTask createIndexTask(Task taskView, Function<Double, Void> updateCallback);
    ScanIndexTask createScanIndexTask(Task taskView, Function<Double, Void> updateCallback);
    ExtractNlpTask createExtractNlpTask(Task taskView, Function<Double, Void> updateCallback);
    EnqueueFromIndexTask createEnqueueFromIndexTask(Task taskView, Function<Double, Void> updateCallback);
    CreateNlpBatchesFromIndex createCreateNlpBatchesFromIndex(Task taskView, Function<Double, Void> updateCallback);
    BatchNlpTask createBatchNlpTask(Task taskView, Function<Double, Void> updateCallback);
    DeduplicateTask createDeduplicateTask(Task taskView, Function<Double, Void> updateCallback);
    ArtifactTask createArtifactTask(Task taskView, Function<Double, Void> updateCallback);

    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);
}
