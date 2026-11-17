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
import org.icij.datashare.utils.DataDirVerifier;
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
    private final Object dataDirArg;
    private final DataDirVerifier dataDirVerifier;

    @Inject
    public ScanTask(DocumentCollectionFactory<Path> factory, PropertiesProvider serverProperties,
                    @Assisted Task<Long> task, @Assisted Function<Double, Void> updateCallback) {
        super(Stage.SCAN, task.getUser(), factory, new PropertiesProvider(task.args), Path.class);
        scanner = new Scanner(outputQueue).configure(options().createFrom(Options.from(task.args)));
        dataDirArg = task.args.get(DATA_DIR_OPT);
        dataDirVerifier = new DataDirVerifier(serverProperties);
    }

    @Override
    public Long call() throws Exception {
        // checked here and not in the constructor: a throw from a reflectively constructed task is requeued forever
        if (!(dataDirArg instanceof String dataDir)) {
            throw new IllegalArgumentException("dataDir must be a path");
        }
        Path path = Paths.get(dataDir);
        if (!dataDirVerifier.allowed(path)) {
            throw new IllegalArgumentException("dataDir must be inside " + dataDirVerifier.value());
        }
        super.call();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        return scannerVisitor.call();
    }
}
