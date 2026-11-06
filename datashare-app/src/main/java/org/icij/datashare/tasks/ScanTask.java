package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.TaskGroupType;
import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.asynctasks.temporal.ActivityOpts;
import org.icij.datashare.asynctasks.temporal.TemporalSingleActivityWorkflow;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.extract.Scanner;
import org.icij.extract.ScannerVisitor;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;

@TemporalSingleActivityWorkflow(name = "scan-documents", activityOptions = @ActivityOpts(timeout = "P7D"))
@OptionsClass(Scanner.class)
@TaskGroup(TaskGroupType.Java)
public class ScanTask extends PipelineTask<Path> {
    private final Scanner scanner;
    private final Path path;

    @Inject
    public ScanTask(DocumentCollectionFactory<Path> factory, @Assisted Task<Long> task,
                    @Assisted Function<Double, Void> updateCallback) {
        super(Stage.SCAN, task.getUser(), factory, new PropertiesProvider(task.args), Path.class);
        scanner = new Scanner(outputQueue).configure(options().createFrom(Options.from(task.args)));
        path = Paths.get((String) task.args.get(DATA_DIR_OPT));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        try {
            Long queued = scannerVisitor.call();
            // ScannerVisitor ends the walk on interrupt and returns normally, which would record a cancelled scan
            // as DONE. A queue-full stop interrupts the walk too, so only a requested cancel counts.
            if (Thread.interrupted() && isCancelled()) {
                throw cancelled();
            }
            return queued;
        } catch (RuntimeException e) {
            if (isCancelled() && causedByInterrupt(e)) {
                Thread.interrupted();
                throw cancelled();
            }
            throw e;
        }
    }

    private InterruptedException cancelled() {
        return new InterruptedException("scan of " + path + " cancelled");
    }
}
