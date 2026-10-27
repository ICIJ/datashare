package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import java.util.function.Function;

/**
 * Re-detects the language of every document referenced in the LANGUAGE queue and rewrites the
 * language field in place, for corpora indexed by an older guesser. It detects on exactly what
 * index time detects on ({@link LanguageGuesser#guess(String, java.nio.file.Path, Object, int)}), file-name
 * fallback included, so a re-run cannot downgrade a document the INDEX stage got right.
 */
@TemporalSingleActivityWorkflow(name = "language", activityOptions = @ActivityOpts(timeout = "P1D"))
@TaskGroup(TaskGroupType.Java)
public class LanguageDetectTask extends StageTask<String, Void> {
    @Inject
    public LanguageDetectTask(DocumentCollectionFactory<String> factory, StageRegistry registry,
                              final UpstreamGate.Factory gateFactory, @Assisted Task<Long> taskView,
                              @Assisted final Function<Double, Void> updateCallback) {
        super(Stage.LANGUAGE, taskView.getUser(), factory, new PropertiesProvider(taskView.args), String.class,
              gateFactory.forTask(taskView), registry);
    }

    /** Declared here, not only inherited: TemporalWorkflowGenerator reads the output type from the annotated class. */
    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
