package org.icij.datashare.cli;

import com.google.inject.Injector;
import org.icij.datashare.ProdServiceModule;
import org.icij.datashare.TaskFactory;
import org.icij.datashare.TaskManager;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.Spewer;
import org.icij.task.Options;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;

import static com.google.inject.Guice.createInjector;
import static java.lang.Boolean.parseBoolean;
import static java.lang.String.valueOf;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.cli.DatashareCli.Stage.INDEX;
import static org.icij.datashare.cli.DatashareCli.Stage.SCAN;
import static org.icij.datashare.cli.DatashareCliOptions.*;
import static org.icij.datashare.text.nlp.Pipeline.Type.parseAll;

public class CliApp {
    static Logger logger = LoggerFactory.getLogger(CliApp.class);

    public static void start(Properties properties) throws Exception {
        Injector injector = createInjector(new ProdServiceModule(properties));
        TaskManager taskManager = injector.getInstance(TaskManager.class);
        TaskFactory taskFactory = injector.getInstance(TaskFactory.class);
        Set<DatashareCli.Stage> stages = stream(properties.getProperty(STAGES_OPT).
                split(valueOf(ARG_VALS_SEP))).map(DatashareCli.Stage::valueOf).collect(toSet());
        Pipeline.Type[] nlpPipelines = parseAll(properties.getProperty(NLP_PIPELINES_OPT));
        Indexer indexer = injector.getInstance(Indexer.class);

        if (resume(properties)) {
            DocumentQueue queue = injector.getInstance(DocumentQueue.class);

            if (indexer.search(Document.class).withSource(false).without(nlpPipelines).execute().count() == 0 &&
                    queue.isEmpty()) {
                logger.info("nothing to resume, exiting normally");
                System.exit(0);
            }
        }

        if (stages.contains(SCAN) && !resume(properties)) {
            taskManager.startTask(taskFactory.createScanTask(Paths.get(properties.getProperty(SCANNING_INPUT_DIR_OPT)), Options.from(properties)));
        }

        if (stages.contains(INDEX)) {
            taskManager.startTask(taskFactory.createSpewTask(Options.from(properties)), () -> {
                closeAndLogException(injector.getInstance(Spewer.class)).run();
                closeAndLogException(injector.getInstance(DocumentQueue.class)).run();
            });
        } else {
            injector.getInstance(Spewer.class).close();
            injector.getInstance(DocumentQueue.class).close();
        }

        if (stages.contains(DatashareCli.Stage.NLP)) {
            for (Pipeline.Type nlp : nlpPipelines) {
                Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(nlp.getClassName());
                taskManager.startTask(taskFactory.createNlpTask(injector.getInstance(pipelineClass)));
            }
            if (resume(properties)) {
                taskManager.startTask(taskFactory.resumeNlpTask());
            }
        } else {
            indexer.close();
        }
        taskManager.shutdownAndAwaitTermination(10, SECONDS);
    }

    @NotNull
    protected static Runnable closeAndLogException(AutoCloseable closeable) {
        return () -> {
            try {
                closeable.close();
            } catch (Exception e) {
                logger.error("error while closing", e);
            }
        };
    }

    protected static boolean resume(Properties properties) {
        return parseBoolean(properties.getProperty(RESUME_OPT, "false"));
    }
}
