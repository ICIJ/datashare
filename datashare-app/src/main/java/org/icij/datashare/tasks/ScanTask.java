package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.extract.Scanner;
import org.icij.extract.ScannerVisitor;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;

@OptionsClass(Scanner.class)
public class ScanTask extends PipelineTask<Path> {
    private final Scanner scanner;
    private final Path path;

    @Inject
    public ScanTask(DocumentCollectionFactory<Path> factory, @Assisted TaskView<Long> task, @Assisted BiFunction<String, Double, Void> updateCallback) {
        super(Stage.SCAN, task.user, factory, new PropertiesProvider(task.properties), Path.class);
        scanner = new Scanner(outputQueue).configure(options().createFrom(Options.from(task.properties)));
        path = Paths.get((String)task.properties.get(DatashareCliOptions.DATA_DIR_OPT));
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
