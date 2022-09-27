package org.icij.datashare.file;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonValue;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Root class for composite pattern
 * https://en.wikipedia.org/wiki/Composite_pattern
 *
 */
public abstract class AbstractFileReport {
    public enum Type {
        DIRECTORY, FILE;

        @JsonValue
        public String toValue() {
            return name().toLowerCase();
        }

    }
    @JsonIgnore
    protected final File file;
    @JsonIgnore
    protected final BasicFileAttributes fileAttributes;

    public AbstractFileReport(File file) throws IOException {
        this.file = file;
        BasicFileAttributes fat;
        try {
            fat = Files.readAttributes(file.toPath(), PosixFileAttributes.class);
        } catch (UnsupportedOperationException u) {
            fat = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        }
        fileAttributes = fat;
    }

    public AbstractFileReport(File file, BasicFileAttributes fileAttributes) {
        this.file = file;
        this.fileAttributes = fileAttributes;
    }
    abstract Type getType();
    protected String fileProt () {
        if (fileAttributes instanceof PosixFileAttributes) {
            return PosixFilePermissions.toString(((PosixFileAttributes) fileAttributes).permissions());
        } else if (fileAttributes instanceof DosFileAttributes){
            return dosFileAttributeToString((DosFileAttributes)fileAttributes);
        } else {
            throw new IllegalStateException("Unknown file attributes : " + fileAttributes);
        }
    }
    @NotNull
    private String dosFileAttributeToString(DosFileAttributes fileAttributes) {
        StringBuilder sb = new StringBuilder(9);
        if (fileAttributes.isReadOnly()) {
            sb.append('r');
        } else {
            sb.append('w');
        }
        return sb.toString();
    }

    public long getSize() { return fileAttributes.size(); }
    public String getName() { return file.getPath(); }
}
