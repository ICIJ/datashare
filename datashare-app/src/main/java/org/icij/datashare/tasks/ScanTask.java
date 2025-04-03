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
    public ScanTask(DocumentCollectionFactory<Path> factory, @Assisted DatashareTask<Long> task, @Assisted Function<Double, Void> updateCallback) {
        super(Stage.SCAN, task.getUser(), factory, new PropertiesProvider(task.getArgs()), Path.class);
        scanner = new Scanner(outputQueue).configure(options().createFrom(Options.from(task.getArgs())));
        path = Paths.get((String)task.getArgs().get(DatashareCliOptions.DATA_DIR_OPT));
    }

    @Override
    public Long call() throws Exception {
        super.call();
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        Long scanned = scannerVisitor.call();
        outputQueue.add(PATH_POISON);
        return scanned;
    }
}
