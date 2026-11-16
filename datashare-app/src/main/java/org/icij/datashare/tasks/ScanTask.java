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
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DATA_DIR;

@TemporalSingleActivityWorkflow(name = "scan-documents", activityOptions = @ActivityOpts(timeout = "P7D"))
@OptionsClass(Scanner.class)
@TaskGroup(TaskGroupType.Java)
public class ScanTask extends PipelineTask<Path> {
    private final Scanner scanner;
    private final Path path;
    private final Path allowedRoot;

    @Inject
    public ScanTask(DocumentCollectionFactory<Path> factory, PropertiesProvider serverProperties,
                    @Assisted Task<Long> task, @Assisted Function<Double, Void> updateCallback) {
        super(Stage.SCAN, task.getUser(), factory, new PropertiesProvider(task.args), Path.class);
        scanner = new Scanner(outputQueue).configure(options().createFrom(Options.from(task.args)));
        path = Paths.get((String) task.args.get(DATA_DIR_OPT));
        allowedRoot = Paths.get(serverProperties.get(DATA_DIR_OPT).orElse(DEFAULT_DATA_DIR));
    }

    @Override
    public Long call() throws Exception {
        // checked here and not in the constructor: a throw from a reflectively constructed task is requeued forever
        if (!path.toAbsolutePath().normalize().startsWith(allowedRoot.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("dataDir must be inside " + allowedRoot);
        }
        super.call();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        return scannerVisitor.call();
    }
}
