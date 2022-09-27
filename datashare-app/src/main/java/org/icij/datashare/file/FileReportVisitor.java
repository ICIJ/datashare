package org.icij.datashare.file;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Stack;

public class FileReportVisitor extends SimpleFileVisitor<Path> {
    private final Stack<DirectoryReport> dirStack = new Stack<>();
    private final int depth;

    public FileReportVisitor(DirectoryReport root) { this(root, 1); }
    public FileReportVisitor(DirectoryReport root, int depth) {
        this.depth = depth;
        dirStack.push(root);
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
        // we'd like to call FileReport(dir.toFile(), attrs) but in that case they are BasicFileAttributes
        dirStack.peek().add(new FileReport(file.toFile()));
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
        if (!dir.toFile().equals(dirStack.peek().file)) {
            // we'd like to call FileReport(dir.toFile(), attrs) but in that case they are BasicFileAttributes
            DirectoryReport dirReport = new DirectoryReport(dir.toFile());
            dirStack.peek().add(dirReport);
            if (dirStack.size() < depth) {
                dirStack.push(dirReport);
                return FileVisitResult.CONTINUE;
            } else {
                return FileVisitResult.SKIP_SUBTREE;
            }
        }
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
        dirStack.pop();
        return FileVisitResult.CONTINUE;
    }
}