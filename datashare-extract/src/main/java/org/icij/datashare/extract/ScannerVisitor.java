package org.icij.datashare.extract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.Callable;

class ScannerVisitor extends SimpleFileVisitor<Path> implements Callable<Long> {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ArrayDeque<PathMatcher> includeMatchers = new ArrayDeque<>();
    private final ArrayDeque<PathMatcher> excludeMatchers = new ArrayDeque<>();
    private final Path path;
    private final ScanCallback callback;
    private long scanned = 0;
    private Integer maxDepth = Integer.MAX_VALUE;
    private boolean followLinks = false;

    public ScannerVisitor(final Path path, final ScanCallback callback) {
        this.path = path;
        this.callback = callback;
    }

    /**
     * Recursively walks the file tree of a directory, calling the callback for each visited file.
     *
     * @return the number of files for which the callback was successfully invoked
     */
    @Override
    public Long call() throws IOException {
        final Set<FileVisitOption> fileVisitOptions;

        if (followLinks) {
            fileVisitOptions = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        } else {
            fileVisitOptions = EnumSet.noneOf(FileVisitOption.class);
        }

        logger.atInfo().log("Starting scan of: \"{}\".", path);
        Files.walkFileTree(path, fileVisitOptions, maxDepth, this);

        logger.atInfo().log("Completed scan of: \"{}\".", path);
        return scanned;
    }

    void exclude(final PathMatcher matcher) {
        excludeMatchers.add(matcher);
    }

    void include(final PathMatcher matcher) {
        includeMatchers.add(matcher);
    }

    public void setFollowLinks(boolean followLinks) {
        this.followLinks = followLinks;
    }

    public void setMaxDepth(Integer maxDepth) {
        this.maxDepth = maxDepth;
    }

    boolean shouldExclude(final Path path) {
        return matches(path, excludeMatchers);
    }

    boolean shouldInclude(final Path path) {
        return includeMatchers.isEmpty() || matches(path, includeMatchers);
    }

    private boolean matches(final Path path, final ArrayDeque<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FileVisitResult preVisitDirectory(final Path directory, final BasicFileAttributes attributes) {
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().log("Scanner interrupted. Terminating job.");
            return FileVisitResult.TERMINATE;
        }

        if (shouldExclude(directory)) {
            return FileVisitResult.SKIP_SUBTREE;
        }

        logger.atInfo().log("Entering directory: \"{}\".", directory);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) {
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().log("Scanner interrupted. Terminating job.");
            return FileVisitResult.TERMINATE;
        }

        // When maxDepth truncates recursion exactly at a directory, walkFileTree calls
        // visitFile for that directory instead of preVisitDirectory/postVisitDirectory,
        // since it won't be entered. Skip it: only regular files are scanned documents.
        if (attributes.isDirectory()) {
            return FileVisitResult.CONTINUE;
        }

        if (attributes.isSymbolicLink()) {
            if (followLinks) {
                logger.atWarn().log("Unable to read attributes of symlink target: \"{}\". Skipping.", file);
            }
            return FileVisitResult.CONTINUE;
        }

        if (!shouldInclude(file) || shouldExclude(file)) {
            return FileVisitResult.CONTINUE;
        }
        scanned++;

        try {
            callback.onFile(file);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.atWarn().log("Interrupted. Terminating scanner.");
            return FileVisitResult.TERMINATE;
        } catch (Exception e) {
            logger.atError().log("Exception while processing file: \"{}\" : {}.", file, e.getMessage());
        }

        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(final Path file, final IOException e) {
        if (!shouldExclude(file)) {
            logger.error(String.format("Unable to read attributes of file: \"%s\".", file), e);
        }
        return FileVisitResult.CONTINUE;
    }

}
