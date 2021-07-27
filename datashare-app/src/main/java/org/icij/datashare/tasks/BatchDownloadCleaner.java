package org.icij.datashare.tasks;

import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.time.DatashareTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static java.util.Arrays.stream;
import static java.util.Objects.requireNonNull;
import static java.util.regex.Pattern.compile;

public class BatchDownloadCleaner implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Pattern filePattern = compile(BatchDownload.ZIP_FORMAT.replace("%s", "[a-z0-9\\.:Z\\-\\[GMT\\]]+"));
    private final Path downloadDir;
    private final int delaySeconds;

    public BatchDownloadCleaner(Path downloadDir, int delaySeconds) {
        this.downloadDir = downloadDir;
        this.delaySeconds = delaySeconds;
    }

    @Override
    public void run() {
        logger.info("deleting temporary zip files from {}", downloadDir);
        stream(requireNonNull(downloadDir.toFile().listFiles()))
                .filter(f -> filePattern.matcher(f.getName()).matches())
                .filter(f -> DatashareTime.getInstance().currentTimeMillis() - f.lastModified() >= delaySeconds * 1000L)
                .forEach(File::delete);
    }
}
