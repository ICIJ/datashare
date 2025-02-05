package org.icij.datashare;

import org.icij.datashare.asynctasks.TaskManager;
import org.icij.datashare.cli.CliExtensionService;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.ArtifactTask;
import org.icij.datashare.tasks.CreateNlpBatchesFromIndex;
import org.icij.datashare.tasks.BatchNlpTask;
import org.icij.datashare.tasks.DatashareTaskFactory;
import org.icij.datashare.tasks.DeduplicateTask;
import org.icij.datashare.tasks.EnqueueFromIndexTask;
import org.icij.datashare.tasks.ExtractNlpTask;
import org.icij.datashare.tasks.IndexTask;
import org.icij.datashare.tasks.ScanIndexTask;
import org.icij.datashare.tasks.ScanTask;
import org.icij.datashare.text.indexing.Indexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.propertiesToMap;
import static org.icij.datashare.cli.DatashareCliOptions.CREATE_INDEX_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.CRE_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEL_API_KEY_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.GET_API_KEY_OPT;
import static org.icij.datashare.user.User.localUser;
import static org.icij.datashare.user.User.nullUser;

class CliApp {
    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);

    static void start(Properties properties) throws Exception {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(properties));
        process(extensionService, properties);
        process(new PluginService(new PropertiesProvider(properties), extensionService), properties);
        try (CommonMode commonMode = CommonMode.create(properties)) {
            List<CliExtension> extensions = CliExtensionService.getInstance().getExtensions();

            logger.info("found {} CLI extension(s)", extensions.size());
            if (extensions.size() == 1 && extensions.get(0).identifier().equals(properties.get("ext"))) {
                CliExtension extension = extensions.get(0);
                extension.init(commonMode::createChildInjector);
                extension.run(properties);
                System.exit(0);
            }
            runTaskWorker(commonMode, properties);
        }
    }

    private static void process(DeliverableService<?> deliverableService, Properties properties) throws IOException {
        String listPattern = deliverableService.getListOpt(properties);
        if (listPattern != null) {
            listPattern = listPattern.equalsIgnoreCase("true") ? ".*":listPattern;
            deliverableService.list(listPattern).forEach(DeliverablePackage::displayInformation);
        } else if(deliverableService.getInstallOpt(properties) != null) {
            deliverableService.downloadAndInstallFromCli(properties);
        } else if (deliverableService.getDeleteOpt(properties) != null) {
            deliverableService.deleteFromCli(properties);
        } else return;
        System.exit(0);
    }

    private static void runTaskWorker(CommonMode mode, Properties properties) throws Exception {
        TaskManager taskManager = mode.get(TaskManager.class);
        DatashareTaskFactory taskFactory = mode.get(DatashareTaskFactory.class);
        Indexer indexer = mode.get(Indexer.class);

        if (properties.getProperty(CREATE_INDEX_OPT) != null) {
            indexer.createIndex(properties.getProperty(CREATE_INDEX_OPT));
            System.exit(0);
        }

        if (properties.getProperty(CRE_API_KEY_OPT) != null) {
            String userName = properties.getProperty(CRE_API_KEY_OPT);
            String secretKey = taskFactory.createGenApiKey(localUser(userName)).call();
            logger.info("generated secret key for user {} (store it somewhere safe, datashare cannot retrieve it later): {}", userName, secretKey);
            System.exit(0);
        }

        if (properties.getProperty(GET_API_KEY_OPT) != null) {
            String userName = properties.getProperty(GET_API_KEY_OPT);
            String hashedKey = taskFactory.createGetApiKey(localUser(userName)).call();
            if ((hashedKey == null)) {
                logger.info("no user {} exists", userName);
            } else {
                logger.info("hashed key for user {} is {}", userName, hashedKey);
            }
            System.exit(0);
        }

        if (properties.getProperty(DEL_API_KEY_OPT) != null) {
            String userName = properties.getProperty(DEL_API_KEY_OPT);
            taskFactory.createDelApiKey(localUser(userName)).call();
            System.exit(0);
        }

        PipelineHelper pipeline = new PipelineHelper(new PropertiesProvider(properties));
        logger.info("executing {}", pipeline);
        if (pipeline.has(Stage.DEDUPLICATE)) {
            taskManager.startTask(DeduplicateTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.SCANIDX)) {
            taskManager.startTask(ScanIndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.SCAN)) {
            taskManager.startTask(ScanTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.INDEX)) {
            taskManager.startTask(IndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.ENQUEUEIDX)) {
            taskManager.startTask(EnqueueFromIndexTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.CREATENLPBATCHESFROMIDX)) {
            taskManager.startTask(CreateNlpBatchesFromIndex.class.getName(), nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.NLP)) {
            taskManager.startTask(ExtractNlpTask.class, nullUser(), propertiesToMap(properties));
        }

        if (pipeline.has(Stage.ARTIFACT)) {
            taskManager.startTask(ArtifactTask.class, nullUser(), propertiesToMap(properties));
        }
        taskManager.awaitTermination(Integer.MAX_VALUE, SECONDS);
        taskManager.shutdown();
    }
}
