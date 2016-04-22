package org.icij.datashare.util.io;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by julien on 4/22/16.
 */
public class FileSystem {

    public static List<Path> listFilesInDirectory(Path directory) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{txt,pdf,doc,docx,rtf,msg,eml,tif}")) {
            for (Path entry: stream) {
                paths.add(entry);
            }
        } catch (DirectoryIteratorException | IOException e) {
            throw new IOException("Failes to list directory " + directory + e.getMessage(), e.getCause());
        }
        return paths;
    }

}
