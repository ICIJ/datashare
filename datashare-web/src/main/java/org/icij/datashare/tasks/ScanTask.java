package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.extract.Scanner;
import org.icij.extract.ScannerVisitor;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.queue.DocumentQueue;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;

import java.nio.file.Path;

@OptionsClass(Scanner.class)
@OptionsClass(DocumentFactory.class)
public class ScanTask extends DefaultTask<Path> {
    private final Scanner scanner;
    private final Path path;

    @Inject
    public ScanTask(final DocumentQueue queue, @Assisted Path path, @Assisted final Options<String> userOptions) {
        this.path = path;
        Options<String> allOptions = options().createFrom(userOptions);
        scanner = new Scanner(new DocumentFactory(allOptions), queue).configure(allOptions);
    }

    @Override
    public Path call() throws Exception {
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        return scannerVisitor.call();
    }
}
