package org.icij.datashare.tasks;

import com.fasterxml.jackson.databind.annotation.JsonAppend;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.function.TerFunction;
import org.icij.datashare.text.Document;
import org.icij.datashare.user.User;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.function.BiFunction;

public interface TaskFactory {
    BatchSearchLoop createBatchSearchLoop();
    BatchDownloadLoop createBatchDownloadLoop();
    BatchSearchRunner createBatchSearchRunner(BatchSearch batchSearch, TerFunction<String, String, List<Document>, Boolean> resultConsumer);
    BatchDownloadRunner createDownloadRunner(TaskView<?> batchDownload, BiFunction<String, Double, Void> updateCallback);
    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);

    ScanIndexTask createScanIndexTask(final User user, final Properties properties);
    ScanTask createScanTask(final User user, final Path path, Properties properties);
    IndexTask createIndexTask(final User user, final Properties properties);
    ExtractNlpTask createNlpTask(final User user, final Properties properties);
    EnqueueFromIndexTask createEnqueueFromIndexTask(final User user, final Properties properties);
    DeduplicateTask createDeduplicateTask(User user);
}
