package org.icij.datashare;

import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ResumeNlpTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.task.Options;

import java.nio.file.Path;

public interface TaskFactory {
    IndexTask createSpewTask(final Options<String> options);
    ScanTask createScanTask(final Path path, final Options<String> options);
    ResumeNlpTask resumeNlpTask();
    NlpApp createNlpTask(AbstractPipeline pipeline);
}
