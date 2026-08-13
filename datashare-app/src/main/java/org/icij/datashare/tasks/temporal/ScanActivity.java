package org.icij.datashare.tasks.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.io.IOException;
import java.nio.file.Path;

@ActivityInterface
public interface ScanActivity {
    @ActivityMethod(name = "scan")
    Long run(final org.icij.datashare.extract.ScanOptions options, final Path path) throws IOException;
}
