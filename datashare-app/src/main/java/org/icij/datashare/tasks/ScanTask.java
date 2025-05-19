package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.TaskGroupType;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroup;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.extract.Scanner;
import org.icij.extract.ScannerVisitor;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;

import java.nio.file.Path;
import java.nio.file.Paths;

@OptionsClass(Scanner.class)
@TaskGroup(TaskGroupType.Java)
public class ScanTask extends PipelineTask<Path> {
    private final Scanner scanner;
    private final Path path;

    @Inject
    public ScanTask(DocumentCollectionFactory<Path> factory, @Assisted Task task, @Assisted Function<Double, Void> ignored) {
        super(Stage.SCAN, task.getUser(), factory, new PropertiesProvider(task.args), Path.class);
        scanner = new Scanner(outputQueue).configure(options().createFrom(Options.from(task.args)));
        path = Paths.get((String)task.args.get(DatashareCliOptions.DATA_DIR_OPT));
    }

    @Override
    public Long runTask() throws Exception {
        super.runTask();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        Long scanned = scannerVisitor.call();
        outputQueue.add(PATH_POISON);
        return scanned;
    }
}
