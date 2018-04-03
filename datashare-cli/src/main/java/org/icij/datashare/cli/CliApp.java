package org.icij.datashare.cli;

import com.google.inject.Injector;
import org.icij.datashare.ProdServiceModule;
import org.icij.datashare.TaskFactory;
import org.icij.datashare.TaskManager;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;

import static com.google.inject.Guice.createInjector;
import static java.lang.Boolean.parseBoolean;
import static java.lang.String.valueOf;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.cli.DatashareCli.Stage.*;
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

        if (resume(properties)) {
            DocumentQueue queue = injector.getInstance(DocumentQueue.class);
            Indexer indexer = injector.getInstance(Indexer.class);

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
            taskManager.startTask(taskFactory.createSpewTask(Options.from(properties)));
        }
        if (stages.contains(NLP)) {
            for (Pipeline.Type nlp : nlpPipelines) {
                Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(nlp.getClassName());
                taskManager.startTask(new NlpApp().withNlp(pipelineClass).withIndexer(ElasticsearchIndexer.class).withProperties(properties));
            }
            if (resume(properties)) {
                taskManager.startTask(taskFactory.resumeNerTask(properties));
            }
        }
        taskManager.shutdownAndAwaitTermination(Integer.MAX_VALUE, HOURS);
    }

    protected static boolean resume(Properties properties) {
        return parseBoolean(properties.getProperty(RESUME_OPT, "false"));
    }
}
