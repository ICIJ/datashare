package org.icij.datashare;

import org.icij.datashare.extract.ScanTask;
import org.icij.datashare.extract.SpewTask;
import org.icij.task.Options;

import java.nio.file.Path;

public interface TaskFactory {
    SpewTask createSpewTask(final Options<String> options);
    ScanTask createScanTask(final Path path, final Options<String> options);
}
