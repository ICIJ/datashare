package org.icij.datashare;

import com.google.inject.Injector;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchDownloadLoop;
import org.icij.datashare.tasks.BatchSearchLoop;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.text.indexing.Indexer;

import java.util.Properties;

import static com.google.inject.Guice.createInjector;

public class BatchSearchApp {
    public static void start(Properties properties) throws Exception {
        Injector injector = createInjector(CommonMode.create(properties));
        BatchSearchLoop batchSearchLoop = injector.getInstance(TaskFactory.class).createBatchSearchLoop();
        batchSearchLoop.requeueDatabaseBatches();
        batchSearchLoop.run();
        batchSearchLoop.close();
        injector.getInstance(Indexer.class).close(); // to avoid being blocked
    }
}
