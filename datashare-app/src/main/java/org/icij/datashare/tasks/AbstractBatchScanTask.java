package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import org.icij.datashare.asynctasks.Task;
import org.icij.extract.Scanner;
import org.icij.extract.io.file.DosHiddenFileMatcher;
import org.icij.extract.io.file.PosixHiddenFileMatcher;
import org.icij.extract.io.file.SystemFileMatcher;
import org.icij.task.DefaultTask;
import org.icij.task.Options;
import org.icij.task.annotation.OptionsClass;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@OptionsClass(Scanner.class)
public abstract class AbstractBatchScanTask<V extends Serializable> extends DefaultTask<V> implements Callable<V> {
    protected final Task<V> task;
    protected final BatchScanVisitor visitor;
    private final ArrayDeque<String> includeGlobs;
    private final ArrayDeque<String> excludeGlobs;
    private final Path dataDir;
    private boolean ignoreHiddenFiles;
    private boolean ignoreSystemFiles;

    public AbstractBatchScanTask(Task<V> task) {
        this.task = task;
        Options<String> options = options().createFrom(Options.from(task.args));
        int batchSize = Optional.ofNullable(task.args.get("batchSize"))
            .map(b -> Integer.parseInt((String) b))
            .orElseThrow(() -> new NullPointerException("missing batch size"));
        Path dataDir = Optional.ofNullable(task.args.get("dataDir"))
            .map(p -> Path.of((String) p))
            .orElseThrow(() -> new NullPointerException("missing dataDir"));
        Integer maxDepth = options.valueIfPresent("maxDepth").map(Integer::parseInt).orElse(null);
        Boolean followSymlinks = options.valueIfPresent("followSymlinks").map(Boolean::parseBoolean).orElse(null);
        this.dataDir = dataDir;
        this.visitor = new BatchScanVisitor(dataDir, batchSize, maxDepth, followSymlinks);
        this.includeGlobs = new ArrayDeque<>();
        this.excludeGlobs = new ArrayDeque<>();
        configureScanVisitor();
    }

    private void configureScanVisitor() {
        options.get("includeOSFiles").parse().asBoolean().ifPresent(this::ignoreSystemFiles);
        options.get("includeHiddenFiles").parse().asBoolean().ifPresent(this::ignoreHiddenFiles);
        options.get("includePattern").values().forEach(this::include);
        options.get("excludePattern").values().forEach(this::exclude);
        FileSystem fileSystem = dataDir.getFileSystem();
        if (this.ignoreHiddenFiles) {
            visitor.exclude(new PosixHiddenFileMatcher());
            if (fileSystem.supportedFileAttributeViews().contains("dos")) {
                visitor.exclude(new DosHiddenFileMatcher());
            }
        }
        if (this.ignoreSystemFiles) {
            visitor.exclude(new SystemFileMatcher());
        }
        for (String excludeGlob : this.excludeGlobs) {
            visitor.exclude(fileSystem.getPathMatcher(excludeGlob));
        }
        for (String includeGlob : this.includeGlobs) {
            visitor.include(fileSystem.getPathMatcher(includeGlob));
        }
    }

    public void ignoreHiddenFiles(boolean ignoreHiddenFiles) {
        this.ignoreHiddenFiles = ignoreHiddenFiles;
    }

    public void ignoreSystemFiles(boolean ignoreSystemFiles) {
        this.ignoreSystemFiles = ignoreSystemFiles;
    }

    public void include(String pattern) {
        this.includeGlobs.add("glob:" + pattern);
    }

    public void exclude(String pattern) {
        this.excludeGlobs.add("glob:" + pattern);
    }

    public static class BatchScanVisitor extends SimpleFileVisitor<Path> {
        private final Logger logger = LoggerFactory.getLogger(this.getClass());

        private final ArrayDeque<PathMatcher> includeMatchers = new ArrayDeque<>();
        private final ArrayDeque<PathMatcher> excludeMatchers = new ArrayDeque<>();

        private final Path rootPath;
        private final List<List<String>> batches;
        private final boolean followLinks;
        private final int batchSize;
        private List<Path> currentBatch;
        private final int maxDepth;

        public BatchScanVisitor(Path rootPath, int batchSize, Integer maxDepth, Boolean followLinks) {
            this.rootPath = rootPath;
            this.maxDepth = Optional.ofNullable(maxDepth).orElse(Integer.MAX_VALUE);
            this.followLinks = Optional.ofNullable(followLinks).orElse(false);
            this.batchSize = batchSize;
            this.batches = new ArrayList<>();
            this.currentBatch = new ArrayList<>(batchSize);
        }

        @NotNull
        public FileVisitResult preVisitDirectory(@NotNull Path directory, @NotNull BasicFileAttributes attributes) {
            if (Thread.currentThread().isInterrupted()) {
                this.logger.warn("Scanner interrupted. Terminating job.");
                return FileVisitResult.TERMINATE;
            }
            if (this.shouldExclude(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            this.logger.info("Entering directory: {}", directory);
            return FileVisitResult.CONTINUE;
        }

        @NotNull
        public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) {
            if (Thread.currentThread().isInterrupted()) {
                this.logger.warn("Scanner interrupted. Terminating job.");
                return FileVisitResult.TERMINATE;
            }
            if (attributes.isSymbolicLink()) {
                if (this.followLinks) {
                    this.logger.warn("Unable to read attributes of symlink target: {}. Skipping.", file);
                }
                return FileVisitResult.CONTINUE;
            }
            if (!this.shouldInclude(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (this.shouldExclude(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (this.currentBatch.size() == batchSize) {
                batches.add(this.currentBatch.stream().map(Path::toString).toList());
                this.currentBatch = new ArrayList<>(batchSize);
            }
            this.currentBatch.add(file);
            return FileVisitResult.CONTINUE;
        }

        @NotNull
        public FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException e) {
            if (!this.shouldExclude(file)) {
                this.logger.error("Unable to read attributes of file: {}.", file, e);
            }
            return FileVisitResult.CONTINUE;
        }

        public void consumeBatches(Consumer<List<String>> batchConsumer) throws IOException {

            Set<FileVisitOption> options;
            if (this.followLinks) {
                options = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
            } else {
                options = EnumSet.noneOf(FileVisitOption.class);
            }
            this.logger.info("Starting scan of: {}.", this.rootPath);
            try {
                Files.walkFileTree(this.rootPath, options, this.maxDepth, this);
            } catch (IOException e) {
                this.logger.error("Error while scanning path: {}.", this.rootPath, e);
                throw e;
            }
            this.logger.info("Completed scan of: {}, writing batches !", this.rootPath);
            batches.forEach(batchConsumer);

        }

        void exclude(PathMatcher matcher) {
            this.excludeMatchers.add(matcher);
        }

        void include(PathMatcher matcher) {
            this.includeMatchers.add(matcher);
        }

        boolean shouldExclude(Path path) {
            return this.matches(path, this.excludeMatchers);
        }

        boolean shouldInclude(Path path) {
            return this.includeMatchers.isEmpty() || this.matches(path, this.includeMatchers);
        }

        private boolean matches(Path path, ArrayDeque<PathMatcher> matchers) {
            for (PathMatcher matcher : matchers) {
                if (matcher.matches(path)) {
                    return true;
                }
            }

            return false;
        }
    }
}
