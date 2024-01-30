package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.Scanner;
import org.icij.extract.ScannerVisitor;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;

import java.nio.file.Path;
import java.util.Properties;

@OptionsClass(Scanner.class)
public class ScanTask extends PipelineTask<Path> {
    private final Scanner scanner;
    private final Path path;

    @Inject
    public ScanTask(final DocumentCollectionFactory<Path> factory, @Assisted User user, @Assisted Path path, @Assisted final Properties properties) {
        super(Stage.SCAN, user, new PipelineHelper(new PropertiesProvider()).getOutputQueueNameFor(Stage.SCAN),
                factory, new PropertiesProvider(properties), Path.class);
        this.path = path;
        Options<String> allOptions = options().createFrom(Options.from(properties));
        scanner = new Scanner(queue).configure(allOptions);
    }

    @Override
    public Long call() throws Exception {
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        Long scanned = scannerVisitor.call();
        queue.add(PATH_POISON);
        return scanned;
    }
}
