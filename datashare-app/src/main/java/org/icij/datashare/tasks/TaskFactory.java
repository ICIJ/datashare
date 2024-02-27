package org.icij.datashare.tasks;

import org.icij.datashare.user.User;

import java.nio.file.Path;
import java.util.Properties;
import java.util.function.BiFunction;

public interface TaskFactory {
    TaskRunnerLoop createTaskRunnerLoop();
    BatchDownloadCleaner createBatchDownloadCleaner();
    BatchSearchRunner createBatchSearchRunner(TaskView<?> taskView, BiFunction<String, Double, Void> updateCallback);
    BatchDownloadRunner createBatchDownloadRunner(TaskView<?> taskView, BiFunction<String, Double, Void> updateCallback);
    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);

    ScanIndexTask createScanIndexTask(User user, String reportName);
    ScanTask createScanTask(final User user, final Path path, Properties properties);
    IndexTask createIndexTask(final User user, final Properties properties);
    ExtractNlpTask createNlpTask(final User user, final Properties properties);
    EnqueueFromIndexTask createEnqueueFromIndexTask(final User user, final Properties properties);
    DeduplicateTask createDeduplicateTask(User user);
}
