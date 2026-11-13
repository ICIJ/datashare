package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.nio.file.Path;
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
import org.icij.extract.Scanner;
import org.icij.task.annotation.OptionsClass;

@TemporalSingleActivityWorkflow(name = "scan-documents", activityOptions = @ActivityOpts(timeout = "P7D"))
@OptionsClass(Scanner.class)
@TaskGroup(TaskGroupType.Java)
public class ScanTask extends StageTask<Void, Path> {
    @Inject

    public ScanTask(DocumentCollectionFactory<Path> factory, StageRegistry registry, @Assisted Task<Long> task,
                    @Assisted Function<Double, Void> updateCallback) {
        // a source stage has no input queue (the first stage has no input queue name): the input factory is never used
        super(Stage.SCAN, task.getUser(), null, new PropertiesProvider(task.args), Void.class, UpstreamGate.NONE,
              registry, factory, Path.class);
    }

    @Override
    public Long call() throws Exception {
        return super.call();
    }
}
