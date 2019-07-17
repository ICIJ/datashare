package org.icij.datashare.tasks;

import org.icij.datashare.tasks.FilterTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ResumeNlpTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.datashare.user.User;
import org.icij.task.Options;

import java.nio.file.Path;
import java.util.Properties;

public interface TaskFactory {
    IndexTask createIndexTask(final User user, final Options<String> options);
    ScanTask createScanTask(User user, final Path path, final Options<String> options);
    ResumeNlpTask createResumeNlpTask(final User user);
    NlpApp createNlpTask(User user, AbstractPipeline pipeline, Properties properties, Runnable subscribedCb);
    NlpApp createNlpTask(User user, AbstractPipeline pipeline);
    FilterTask createFilterTask(User user);
}
