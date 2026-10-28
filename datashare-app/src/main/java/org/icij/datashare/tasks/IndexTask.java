package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.monitoring.Monitorable;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.extract.queue.DocumentQueueDrainer;
import org.icij.task.annotation.Option;
import org.icij.task.annotation.OptionsClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.PropertiesProvider.REPORT_NAME_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.OCR_MIN_CONFIDENCE_OPT;

/**
 * The INDEX stage on the queues: it drains the queue of paths with the workers of {@link StageTask}, and the
 * {@link IndexStage} registered for it does the work, the same one the Temporal activity runs. What this task adds is
 * the progress reporting.
 */
@TemporalSingleActivityWorkflow(name = "index-documents", activityOptions = @ActivityOpts(timeout = "P30D"))
@OptionsClass(Extractor.class)
@OptionsClass(DocumentFactory.class)
@OptionsClass(DocumentQueueDrainer.class)
@Option(name = DEFAULT_PROJECT_OPT, description = "the default project name")
@Option(name = "projectName", description = "task project name")
@Option(name = OCR_MIN_CONFIDENCE_OPT,
        description = "OCR confidence, from 0 to 100, under which an image's text is not used to detect its language. Only applies when the OCR records a confidence: the language is chosen per image, or the OCR type is TESS4J")
@TaskGroup(TaskGroupType.Java)
public class IndexTask extends StageTask<Path, String> implements Monitorable {
    private static final Logger logger = LoggerFactory.getLogger(IndexTask.class);
    private final Function<Double, Void> progressCallback;
    private final AtomicLong totalToProcess = new AtomicLong();

    @Inject
    public IndexTask(final DocumentCollectionFactory<Path> factory,
                     final DocumentCollectionFactory<String> outputFactory, final StageRegistry registry,
                     final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                     @Assisted final Function<Double, Void> progressCallback) {
        super(Stage.INDEX, taskView.getUser(), factory, new PropertiesProvider(taskView.args), Path.class,
              gateFactory.forTask(taskView), registry, outputFactory, String.class);
        this.progressCallback = progressCallback;
        setTaskId(taskView.id);
        propertiesProvider.get(REPORT_NAME_OPT).ifPresent(name -> logger.warn(
                "--reportName {} is set but the INDEX stage no longer records what it extracts there", name));
    }

    @Override
    public Long call() throws Exception {
        return super.call();
    }

    @Override
    protected void onEntryProcessed() {
        if (progressCallback != null) {
            progressCallback.apply(getProgressRate());
        }
    }

    @Override
    public double getProgressRate() {
        long total = totalToProcess.accumulateAndGet(inputQueue.size(), Math::max);
        return total == 0 ? 0 : (double) (total - inputQueue.size()) / total;
    }
}
