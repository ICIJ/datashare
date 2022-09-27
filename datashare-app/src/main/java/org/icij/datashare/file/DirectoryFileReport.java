package org.icij.datashare.file;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.*;
import java.util.*;

import static java.lang.String.format;

public class DirectoryFileReport extends AbstractFileReport {
    private final TreeSet<AbstractFileReport> contents = new TreeSet<>(Comparator.comparing(f -> f.file));

    public DirectoryFileReport(File file) throws IOException { super(file); }
    public DirectoryFileReport(File file, BasicFileAttributes fileAttributes) { super(file, fileAttributes); }

    public void add(AbstractFileReport fileReport) {
        if (!file.equals(fileReport.file.getParentFile())) {
            throw new IllegalArgumentException(format("cannot add a file (%s) outside this directory (%s)", fileReport.file, file));
        }
        contents.add(fileReport);
    }

    public List<AbstractFileReport> getContents() { return new ArrayList<>(contents); }

    @Override
    public Type getType() { return Type.DIRECTORY; }

    public String getProt() {
        return "d" + super.fileProt();
    }
}