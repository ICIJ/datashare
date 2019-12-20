package org.icij.datashare.tasks;

import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

public interface TaskFactory {
    ResumeNlpTask createResumeNlpTask(final User user, Set<Pipeline.Type> pipelines);
    NlpApp createNlpTask(User user, AbstractPipeline pipeline, Properties properties, Runnable subscribedCb);
    NlpApp createNlpTask(User user, AbstractPipeline pipeline);
    BatchSearchRunner createBatchSearchRunner(User user);
    ScanIndexTask createScanIndexTask(User user);

    ScanTask createScanTask(User user, String queueName, final Path path, Properties properties);
    IndexTask createIndexTask(final User user, String queueName, final Properties properties);
    FilterTask createFilterTask(User user, String queueName);
    DeduplicateTask createDeduplicateTask(User user, String queueName);
}
