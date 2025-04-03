package org.icij.datashare.tasks;

import java.util.LinkedList;
import org.icij.datashare.user.User;

import java.util.function.Function;


public interface DatashareTaskFactory extends org.icij.datashare.asynctasks.TaskFactory {
    BatchSearchRunner createBatchSearchRunner(DatashareTask<?> taskView, Function<Double, Void> updateCallback);
    BatchDownloadRunner createBatchDownloadRunner(DatashareTask<?> taskView, Function<Double, Void> updateCallback);

    ScanTask createScanTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    IndexTask createIndexTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    ScanIndexTask createScanIndexTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    ExtractNlpTask createExtractNlpTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    EnqueueFromIndexTask createEnqueueFromIndexTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    CreateNlpBatchesFromIndex createBatchEnqueueFromIndexTask(DatashareTask<LinkedList<String>> taskView, Function<Double, Void> updateCallback);
    BatchNlpTask createBatchNlpTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    DeduplicateTask createDeduplicateTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);
    ArtifactTask createArtifactTask(DatashareTask<Long> taskView, Function<Double, Void> updateCallback);

    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);
}
