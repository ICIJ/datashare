package org.icij.datashare.tasks;

import org.icij.datashare.nlp.NlpApp;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

public interface TaskFactory {
    ResumeNlpTask createResumeNlpTask(final User user, Set<Pipeline.Type> pipelines);
    NlpApp createNlpTask(User user, Pipeline pipeline, Properties properties, Runnable subscribedCb);
    NlpApp createNlpTask(User user, Pipeline pipeline);
    BatchSearchRunner createBatchSearchRunner(User user);
    GenApiKeyTask createGenApiKey(User user);
    ScanIndexTask createScanIndexTask(User user, String reportName);

    ScanTask createScanTask(User user, String queueName, final Path path, Properties properties);
    IndexTask createIndexTask(final User user, String queueName, final Properties properties);
    DeduplicateTask createDeduplicateTask(User user, String queueName);
}
