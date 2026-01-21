package org.icij.datashare;

import static java.lang.Boolean.parseBoolean;
import static java.lang.Integer.parseInt;
import static org.icij.datashare.cli.DatashareCliOptions.BROWSER_OPEN_LINK_OPT;

import java.io.IOException;
import java.util.Map;
import net.codestory.http.WebServer;
import org.icij.datashare.asynctasks.TaskAlreadyExists;
import org.icij.datashare.batch.BatchSearch;
import org.icij.datashare.batch.BatchSearchRecord;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.DatashareTaskManager;
import org.icij.datashare.utils.WebBrowserUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebApp {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebApp.class);

    static void start(CommonMode mode) throws Exception {
        new WebServer()
                .withThreadCount(10)
                .withSelectThreads(2)
                .withWebSocketThreads(1)
                .configure(mode.createWebConfiguration())
                .start(parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT)));

        if (mode.isEmbeddedAMQP()) {
            mode.createWorkers();
        }

        int port = parseInt(mode.properties().getProperty(PropertiesProvider.TCP_LISTEN_PORT_OPT));
        boolean shouldOpenBrowser = parseBoolean(mode.properties().getProperty(BROWSER_OPEN_LINK_OPT));
        WebBrowserUtils.openBrowser(port, shouldOpenBrowser);

        requeueDatabaseBatchSearches(mode.get(BatchSearchRepository.class), mode.get(DatashareTaskManager.class));
    }

    private static void requeueDatabaseBatchSearches(BatchSearchRepository repository, DatashareTaskManager taskManager) throws IOException {
        for (String batchSearchUuid: repository.getQueued()) {
            BatchSearch batchSearch = repository.get(batchSearchUuid);
            try {
                taskManager.startTask(batchSearchUuid, BatchSearchRunner.class, batchSearch.user, Map.of("batchRecord", new BatchSearchRecord(batchSearch)));
            } catch (TaskAlreadyExists e) {
                LOGGER.info("ignoring already started task <{}>", batchSearchUuid);
            }
        }
    }
}
