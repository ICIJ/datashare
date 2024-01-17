package org.icij.datashare;

import org.icij.datashare.cli.CliExtensionService;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.cli.spi.CliExtension;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManagerMemory;
import org.icij.datashare.tasks.TaskView;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static java.lang.Boolean.parseBoolean;
import static java.util.Collections.singletonList;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.icij.datashare.PropertiesProvider.MAP_NAME_OPTION;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;
import static org.icij.datashare.user.User.localUser;
import static org.icij.datashare.user.User.nullUser;

class CliApp {
    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);

    static void start(Properties properties) throws Exception {
        ExtensionService extensionService = new ExtensionService(new PropertiesProvider(properties));
        process(extensionService, properties);
        process(new PluginService(new PropertiesProvider(properties), extensionService), properties);
        CommonMode commonMode = CommonMode.create(properties);
        List<CliExtension> extensions = CliExtensionService.getInstance().getExtensions();

        logger.info("found {} CLI extension(s)", extensions.size());
        if (extensions.size() == 1 && extensions.get(0).identifier().equals(properties.get("ext"))) {
            CliExtension extension = extensions.get(0);
            extension.init(commonMode::createChildInjector);
            extension.run(properties);
            System.exit(0);
        }
        runTaskRunner(commonMode, properties);
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

    private static void runTaskRunner(CommonMode mode, Properties properties) throws Exception {
        TaskManagerMemory taskManager = mode.get(TaskManagerMemory.class);
        TaskFactory taskFactory = mode.get(TaskFactory.class);

        Pipeline.Type nlpPipeline = Pipeline.Type.parse(properties.getProperty(DatashareCliOptions.NLP_PIPELINE_OPT));
        Indexer indexer = mode.get(Indexer.class);

        if (resume(properties)) {
            RedisUserDocumentQueue<Path> queue = new RedisUserDocumentQueue<>(nullUser(), new PropertiesProvider(properties), Path.class);
            boolean queueIsEmpty = queue.isEmpty();
            queue.close();

            if (indexer.search(singletonList(properties.getProperty("defaultProject")), Document.class).without(nlpPipeline).withSource(false).execute().findAny().isEmpty() && queueIsEmpty) {
                logger.info("nothing to resume, exiting normally");
                System.exit(0);
            }
        }

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
        if (pipeline.has(DatashareCli.Stage.DEDUPLICATE)) {
            taskManager.startTask(taskFactory.createDeduplicateTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.DEDUPLICATE)));
        }

        if (pipeline.has(DatashareCli.Stage.SCANIDX)) {
            TaskView<Long> taskView = taskManager.startTask(taskFactory.createScanIndexTask(nullUser(), ofNullable(properties.getProperty(MAP_NAME_OPTION)).orElse("extract:report")));
            logger.info("scanned {}", taskView.getResult(true));
        }

        if (pipeline.has(DatashareCli.Stage.SCAN) && !resume(properties)) {
            taskManager.startTask(taskFactory.createScanTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.SCAN), Paths.get(properties.getProperty(DatashareCliOptions.DATA_DIR_OPT)), properties),
                    () -> closeAndLogException(mode.get(DocumentQueue.class)).run());
        }

        if (pipeline.has(DatashareCli.Stage.INDEX)) {
            taskManager.startTask(taskFactory.createIndexTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.INDEX), properties),
                    () -> closeAndLogException(mode.get(DocumentQueue.class)).run());
        }

        if (pipeline.has(DatashareCli.Stage.NLP)) {
            if (resume(properties)) {
                taskManager.startTask(taskFactory.createResumeNlpTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.NLP), properties));
            }
            taskManager.startTask(taskFactory.createNlpTask(nullUser(), pipeline.getQueueNameFor(DatashareCli.Stage.NLP), properties),
                    () -> closeAndLogException(mode.get(DocumentQueue.class)).run());
        }
        taskManager.shutdownAndAwaitTermination(Integer.MAX_VALUE, SECONDS);
        indexer.close();
    }

    private static Runnable closeAndLogException(AutoCloseable closeable) {
        return () -> {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.error("error while closing", e);
            }
        };
    }

    private static boolean resume(Properties properties) {
        return parseBoolean(properties.getProperty(DatashareCliOptions.RESUME_OPT, "false"));
    }
}
