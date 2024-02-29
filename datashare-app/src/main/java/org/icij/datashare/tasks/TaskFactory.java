package org.icij.datashare.tasks;

import org.icij.datashare.user.User;

import java.util.function.BiFunction;

public interface TaskFactory {
    TaskRunnerLoop createTaskRunnerLoop();
    BatchDownloadCleaner createBatchDownloadCleaner();

    BatchSearchRunner createBatchSearchRunner(TaskView<?> taskView, BiFunction<String, Double, Void> updateCallback);
    BatchDownloadRunner createBatchDownloadRunner(TaskView<?> taskView, BiFunction<String, Double, Void> updateCallback);

    ScanTask createScanTask(TaskView<Long> taskView, BiFunction<String, Double, Void> updateCallback);
    IndexTask createIndexTask(TaskView<Long> taskView, BiFunction<String, Double, Void> updateCallback);
    ScanIndexTask createScanIndexTask(TaskView<Long> taskView, BiFunction<String, Double, Void> updateCallback);
    ExtractNlpTask createExtractNlpTask(TaskView<Long> taskView, BiFunction<String, Double, Void> updateCallback);
    EnqueueFromIndexTask createEnqueueFromIndexTask(TaskView<Long> taskView, BiFunction<String, Double, Void> updateCallback);
    DeduplicateTask createDeduplicateTask(TaskView<Long> taskView, BiFunction<String, Double, Void> updateCallback);

    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);
}
