package org.icij.datashare.tasks;

import com.google.inject.Inject;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.batch.BatchDownload;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.time.DatashareTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

import static java.util.Arrays.stream;
import static java.util.Optional.ofNullable;
import static java.util.regex.Pattern.compile;
import org.icij.datashare.asynctasks.TaskGroupType;

@TaskGroup(TaskGroupType.Java)
public class BatchDownloadCleaner implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Pattern filePattern = compile(BatchDownload.ZIP_FORMAT.replace("%s", "[a-z0-9\\.:|_Z\\-\\[GMT\\]]+"));
    private final Path downloadDir;
    private final int ttlHour;

    @Inject
    public BatchDownloadCleaner(final PropertiesProvider propertiesProvider) {
        downloadDir = Paths.get(propertiesProvider.getProperties().getProperty(DatashareCliOptions.BATCH_DOWNLOAD_DIR_OPT));
        ttlHour = Integer.parseInt(propertiesProvider.getProperties().getProperty(DatashareCliOptions.BATCH_DOWNLOAD_ZIP_TTL_OPT));
    }

    @Override
    public void run() {
        logger.debug("deleting temporary zip files from {}", downloadDir);
        stream(ofNullable(downloadDir.toFile().listFiles()).orElse(new File[] {}))
                .filter(f -> filePattern.matcher(f.getName()).matches())
                .filter(f -> DatashareTime.getInstance().currentTimeMillis() - f.lastModified() >= ttlHour * 1000L * 60 * 60)
                .forEach(File::delete);
    }
}
