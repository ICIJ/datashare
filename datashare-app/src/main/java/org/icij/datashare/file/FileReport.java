package org.icij.datashare.file;

import java.io.File;
import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributes;

public class FileReport extends AbstractFileReport {
    public FileReport(File file) throws IOException { super(file); }
    public FileReport(File file, BasicFileAttributes fileAttributes) { super(file, fileAttributes); }

    @Override
    public Type getType() { return Type.FILE; }


    public String getProt() {
        return "-" + super.fileProt();
    }
}
