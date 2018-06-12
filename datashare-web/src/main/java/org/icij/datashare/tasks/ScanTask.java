package org.icij.datashare.tasks;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import org.icij.datashare.User;
import org.icij.datashare.extract.RedisUserDocumentQueue;
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
    public ScanTask(@Assisted User user, @Assisted Path path, @Assisted final Options<String> userOptions) {
        this.path = user == null ? path: path.resolve(user.id);
        Options<String> allOptions = options().createFrom(userOptions);
        DocumentQueue queue = new RedisUserDocumentQueue(user, userOptions);
        scanner = new Scanner(new DocumentFactory(allOptions), queue).configure(allOptions);
    }

    @Override
    public Path call() throws Exception {
        ScannerVisitor scannerVisitor = scanner.createScannerVisitor(path);
        return scannerVisitor.call();
    }
}
