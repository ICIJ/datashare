package org.icij.datashare.extract;

import org.icij.extract.io.file.DosHiddenFileMatcher;
import org.icij.extract.io.file.PosixHiddenFileMatcher;
import org.icij.extract.io.file.SystemFileMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Scanner for scanning the directory tree starting at a given path.
 * Unlike {@link org.icij.extract.Scanner}, which pushes each visited path onto a
 * {@link java.util.concurrent.BlockingQueue} for an external consumer, this scanner calls a
 * {@link ScanCallback} synchronously for each file it finds.
 * {@link #scan} blocks until the whole tree has been walked.
 */
public class Scanner {
    private static final Logger logger = LoggerFactory.getLogger(Scanner.class);
    private final ScanCallback callback;
    private final ScanOptions scanOptions;

    public Scanner(final ScanOptions scanOptions, final ScanCallback callback) {
        this.scanOptions = scanOptions;
        this.callback = callback;
    }

    /**
     * Scan the given path, calling the callback for each file found.
     *
     * Blocks until the whole tree has been walked.
     *
     * @param path the path to scan
     * @return the number of files for which the callback was invoked
     */
    public Long scan(final Path path) throws IOException {
        return createScannerVisitor(path).call();
    }

    /**
     * @see #scan(Path)
     */
    public Long scan(final Path[] paths) throws IOException {
        long total = 0;
        for (Path path : paths) {
            total += scan(path);
        }
        return total;
    }

    /**
     * @see #scan(Path[])
     */
    public Long scan(final String[] paths) throws IOException {
        final Path[] _paths = new Path[paths.length];
        for (int i = 0; i < paths.length; i++)
            _paths[i] = Paths.get(paths[i]);
        return scan(_paths);
    }

    private ScannerVisitor createScannerVisitor(Path path) {
        final ScannerVisitor visitor = new ScannerVisitor(path, callback);
        configureScannerVisitor(path, visitor);
        return visitor;
    }

    private void configureScannerVisitor(Path path, ScannerVisitor visitor) {
        final FileSystem fileSystem = path.getFileSystem();
        // In order to make hidden-file-ignoring logic more predictable, always ignore file names starting with a
        // dot, but only ignore DOS hidden files if the file system supports that attribute.
        if (!scanOptions.includeHiddenFiles()) {
            visitor.exclude(new PosixHiddenFileMatcher());
            if (fileSystem.supportedFileAttributeViews().contains("dos")) {
                visitor.exclude(new DosHiddenFileMatcher());
            }
        }

        if (!scanOptions.includeOSFiles()) {
            visitor.exclude(new SystemFileMatcher());
        }

        for (String excludePattern : scanOptions.excludePatterns()) {
            visitor.exclude(fileSystem.getPathMatcher("glob:" + excludePattern));
        }

        for (String includePattern : scanOptions.includePatterns()) {
            visitor.include(fileSystem.getPathMatcher("glob:" + includePattern));
        }

        visitor.setMaxDepth(scanOptions.maxDepth());
        visitor.setFollowLinks(scanOptions.followSymlinks());

        logger.atInfo().log("Queuing scan of: \"{}\".", path);
    }
}
