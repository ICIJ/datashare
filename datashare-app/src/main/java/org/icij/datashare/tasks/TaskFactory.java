package org.icij.datashare.tasks;

import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.function.TerFunction;
import org.icij.datashare.nlp.NlpApp;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

public interface TaskFactory {
    ResumeNlpTask createResumeNlpTask(final User user, Set<Pipeline.Type> pipelines, Properties taskProperties);
    NlpApp createNlpTask(User user, Pipeline pipeline, Properties properties, Runnable subscribedCb);
    NlpApp createNlpTask(User user, Pipeline pipeline);
    BatchSearchLoop createBatchSearchLoop();
    BatchDownloadLoop createBatchDownloadLoop();
    BatchSearchRunner createBatchSearchRunner(BatchSearch batchSearch, TerFunction<String, String, List<Document>, Boolean> resultConsumer);
    BatchDownloadRunner createDownloadRunner(BatchDownload batchDownload, BiFunction<String, Double, Void> updateCallback);
    GenApiKeyTask createGenApiKey(User user);
    DelApiKeyTask createDelApiKey(User user);
    GetApiKeyTask createGetApiKey(User user);

    ScanIndexTask createScanIndexTask(User user, String reportName);
    ScanTask createScanTask(User user, String queueName, final Path path, Properties properties);
    IndexTask createIndexTask(final User user, String queueName, final Properties properties);

    DeduplicateTask createDeduplicateTask(User user, String queueName);
}
