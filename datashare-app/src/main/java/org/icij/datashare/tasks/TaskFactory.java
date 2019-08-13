package org.icij.datashare.tasks;

import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.datashare.user.User;
import org.icij.task.Options;

import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;

public interface TaskFactory {
    IndexTask createIndexTask(final User user, final Options<String> options);
    ScanTask createScanTask(User user, final Path path, final Options<String> options);
    ResumeNlpTask createResumeNlpTask(final User user, Set<Pipeline.Type> pipelines);
    NlpApp createNlpTask(User user, AbstractPipeline pipeline, Properties properties, Runnable subscribedCb);
    NlpApp createNlpTask(User user, AbstractPipeline pipeline);
    FilterTask createFilterTask(User user);
}
