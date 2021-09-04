package org.icij.datashare;

import com.google.inject.Injector;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchDownloadLoop;
import org.icij.datashare.tasks.TaskFactory;

import java.util.Properties;

import static com.google.inject.Guice.createInjector;

public class BatchDownloadApp {
    public static void start(Properties properties) throws Exception {
        Injector injector = createInjector(CommonMode.create(properties));
        BatchDownloadLoop batchDownloadLoop = injector.getInstance(TaskFactory.class).createBatchDownloadLoop();
        batchDownloadLoop.run();
        batchDownloadLoop.close();
    }
}
