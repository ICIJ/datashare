package org.icij.datashare.extract;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.queue.DocumentQueue;
import org.icij.extract.queue.Scanner;
import org.icij.extract.queue.ScannerVisitor;
import org.icij.task.Options;

import java.nio.file.Path;
import java.util.concurrent.Callable;

public class ScanTask implements Callable<Path> {
    private final Scanner scanner;
    private final Path path;

    @Inject
    public ScanTask(final DocumentQueue queue, @Assisted Path path, @Assisted final Options<String> options) {
        this.path = path;
        scanner = new Scanner(new DocumentFactory(options), queue).configure(options);
    }

    @Override
    public Path call() throws Exception {
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        return scannerVisitor.call();
    }
}
