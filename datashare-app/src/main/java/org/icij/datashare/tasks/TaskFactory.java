package org.icij.datashare.tasks;

import org.icij.datashare.user.User;

import java.util.Properties;
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

    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);


    EnqueueFromIndexTask createEnqueueFromIndexTask(final User user, final Properties properties);
    DeduplicateTask createDeduplicateTask(User user);
}
