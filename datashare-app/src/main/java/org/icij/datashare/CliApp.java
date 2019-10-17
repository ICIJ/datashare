package org.icij.datashare;

import com.google.inject.Injector;
import org.icij.datashare.batch.BatchSearchRepository;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.extract.RedisUserDocumentQueue;
import org.icij.datashare.mode.CommonMode;
import org.icij.datashare.tasks.BatchSearchRunner;
import org.icij.datashare.tasks.TaskFactory;
import org.icij.datashare.tasks.TaskManager;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;

import static com.google.inject.Guice.createInjector;
import static java.lang.Boolean.parseBoolean;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;
import static org.icij.datashare.user.User.nullUser;

class CliApp {
    private static final Logger logger = LoggerFactory.getLogger(CliApp.class);

    static void start(Properties properties) throws Exception {
        Injector injector = createInjector(CommonMode.create(properties));
        if (CommonMode.isBatch(properties)) {
            runBatch(injector);
        } else {
            runTaskRunner(injector, properties);
        }
    }

    private static void runBatch(Injector injector) throws Exception {
        new BatchSearchRunner(injector.getInstance(Indexer.class), injector.getInstance(BatchSearchRepository.class), nullUser()).call();
        injector.getInstance(Indexer.class).close();
        injector.getInstance(BatchSearchRepository.class).close();
    }

    private static void runTaskRunner(Injector injector, Properties properties) throws Exception {
        TaskManager taskManager = injector.getInstance(TaskManager.class);
        TaskFactory taskFactory = injector.getInstance(TaskFactory.class);
        Set<DatashareCli.Stage> stages = stream(properties.getProperty(DatashareCliOptions.STAGES_OPT).
                split(String.valueOf(DatashareCliOptions.ARG_VALS_SEP))).map(DatashareCli.Stage::valueOf).collect(toSet());
        Set<Pipeline.Type> nlpPipelines = parseAll(properties.getProperty(DatashareCliOptions.NLP_PIPELINES_OPT));
        Indexer indexer = injector.getInstance(Indexer.class);

        if (resume(properties)) {
            RedisUserDocumentQueue queue = new RedisUserDocumentQueue(nullUser(), Options.from(properties));
            boolean queueIsEmpty = queue.isEmpty();
            queue.close();

            if (indexer.search(properties.getProperty("defaultProject"), Document.class).withSource(false).without(nlpPipelines.toArray(new Pipeline.Type[]{})).execute().count() == 0 && queueIsEmpty) {
                logger.info("nothing to resume, exiting normally");
                System.exit(0);
            }
        }

        if (stages.contains(DatashareCli.Stage.FILTER)) {
            taskManager.startTask(taskFactory.createFilterTask(nullUser()));
        }


        if (stages.contains(DatashareCli.Stage.SCAN) && !resume(properties)) {
            taskManager.startTask(taskFactory.createScanTask(nullUser(), Paths.get(properties.getProperty(DatashareCliOptions.DATA_DIR_OPT)), Options.from(properties)),
                    () -> closeAndLogException(injector.getInstance(DocumentQueue.class)).run());
        }

        if (stages.contains(DatashareCli.Stage.INDEX)) {
            taskManager.startTask(taskFactory.createIndexTask(nullUser(), Options.from(properties)),
                    () -> closeAndLogException(injector.getInstance(DocumentQueue.class)).run());
        }

        if (stages.contains(DatashareCli.Stage.NLP)) {
            for (Pipeline.Type nlp : nlpPipelines) {
                Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(nlp.getClassName());
                taskManager.startTask(taskFactory.createNlpTask(nullUser(), injector.getInstance(pipelineClass)));
            }
            if (resume(properties)) {
                taskManager.startTask(taskFactory.createResumeNlpTask(nullUser(), nlpPipelines));
            }
        }
        taskManager.shutdownAndAwaitTermination(Integer.MAX_VALUE, SECONDS);
        indexer.close();
    }

    @NotNull
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
