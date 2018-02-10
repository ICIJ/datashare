package org.icij.datashare.cli;

import com.google.inject.Injector;
import org.icij.datashare.ProdServiceModule;
import org.icij.datashare.TaskFactory;
import org.icij.datashare.TaskManager;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchIndexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.text.nlp.NlpApp;
import org.icij.datashare.text.nlp.Pipeline;
import org.icij.task.Options;

import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;

import static com.google.inject.Guice.createInjector;
import static java.lang.String.valueOf;
import static java.util.Arrays.stream;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.stream.Collectors.toSet;
import static org.icij.datashare.cli.DataShareCli.Stage.*;
import static org.icij.datashare.cli.DataShareCliOptions.*;

public class CliApp {
    public static void start(Properties properties) throws Exception {
        Injector injector = createInjector(new ProdServiceModule(properties));
        TaskManager taskManager = injector.getInstance(TaskManager.class);
        TaskFactory taskFactory = injector.getInstance(TaskFactory.class);
        Set<DataShareCli.Stage> stages = stream(properties.getProperty(STAGES_OPT).
                split(valueOf(ARG_VALS_SEP))).map(DataShareCli.Stage::valueOf).collect(toSet());

        if (stages.contains(SCAN)) {
            taskManager.startTask(taskFactory.createScanTask(Paths.get(properties.getProperty(SCANNING_INPUT_DIR_OPT)), Options.from(properties)));
        }
        if (stages.contains(INDEX)) {
            taskManager.startTask(taskFactory.createSpewTask(Options.from(properties)));
        }
        if (stages.contains(NLP)) {
            Set<Pipeline.Type> nlpPipelines = stream(properties.getProperty(NLP_PIPELINES_OPT).
                    split(valueOf(ARG_VALS_SEP))).map(Pipeline.Type::valueOf).collect(toSet());

            for (Pipeline.Type nlp : nlpPipelines) {
                Class<? extends AbstractPipeline> pipelineClass = (Class<? extends AbstractPipeline>) Class.forName(nlp.getClassName());
                taskManager.startTask(new NlpApp().withNlp(pipelineClass).withIndexer(ElasticsearchIndexer.class));
            }
        }
        taskManager.shutdownAndAwaitTermination(Integer.MAX_VALUE, HOURS);
    }
}
