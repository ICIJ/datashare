package org.icij.datashare.web;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.*;
import java.util.*;

import static java.lang.String.format;

class FileReport {
    @JsonIgnore
    protected final File file;
    @JsonIgnore
    private final BasicFileAttributes fileAttributes;
    private final List<FileReport> children = new LinkedList<>();

    public enum Type {
        DIRECTORY, FILE;

        @JsonValue
        public String toValue() {
            return name().toLowerCase();
        }

    }
    public FileReport(File file) throws IOException {
        this.file = file;
        BasicFileAttributes fat;
        try {
            fat = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
        } catch (UnsupportedOperationException u) {
            fat = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        }
        fileAttributes = fat;
    }

    public FileReport(File file, BasicFileAttributes fileAttributes) {
        this.file = file;
        this.fileAttributes = fileAttributes;
    }

    public void add(FileReport fileReport) {
        if (file.isFile()) {
            throw new IllegalArgumentException(format("cannot add a file report (%s) on a file report (should be a directory)", fileReport.file));
        }
        if (!file.equals(fileReport.file.getParentFile())) {
            throw new IllegalArgumentException(format("cannot add a file (%s) outside this directory (%s)", fileReport.file, file));
        }
        children.add(fileReport);
    }

    public List<FileReport> getChildren() { return children; }

    public String getName() {
        return file.getPath();
    }

    public Type getType() {
        return file.isFile() ? Type.FILE: Type.DIRECTORY;
    }

    public long getSize() {
        return fileAttributes.size();
    }

    public String getProt() {
        return (file.isFile() ? "-" : "d") + fileProt();
    }

    protected String fileProt () {
        if (fileAttributes instanceof PosixFileAttributes) {
            return PosixFilePermissions.toString(((PosixFileAttributes) fileAttributes).permissions());
        } else if (fileAttributes instanceof DosFileAttributes){
            StringBuilder sb = new StringBuilder(9);
            if (((DosFileAttributes) fileAttributes).isReadOnly()) {
                sb.append('r');
            } else {
                sb.append('w');
            }
            return sb.toString();
        } else {
            throw new IllegalStateException("Unknown file attributes : " + fileAttributes);
        }
    }

    static class FileReportVisitor extends SimpleFileVisitor<Path> {
        private final Stack<FileReport> dirStack = new Stack<>();
        private final int depth;

        FileReportVisitor(FileReport root) { this(root, 1); }
        FileReportVisitor(FileReport root, int depth) {
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
                FileReport dirReport = new FileReport(dir.toFile());
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
}