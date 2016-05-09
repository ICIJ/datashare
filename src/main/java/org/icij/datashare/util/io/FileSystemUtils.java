package org.icij.datashare.util.io;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by julien on 4/22/16.
 */
public class FileSystemUtils {

    public static final int CHAR_BUFFER_SIZE = 8192;


    public static List<Path> listFilesInDirectory(Path directory) throws IOException {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.{txt,pdf,doc,docx,rtf,msg,eml,tif}")) {
            for (Path entry: stream) {
                paths.add(entry);
            }
        } catch (DirectoryIteratorException | IOException e) {
            throw new IOException("Failed to list directory " + directory + e.getMessage(), e.getCause());
        }
        return paths;
    }


    public static void writeToFile(Path filePath, Charset charset, String content) throws IOException {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(filePath), charset)) {
            writer.write(content);
        }
    }

    public static String readFromFile(Path filePath, Charset charset) throws IOException {
        StringBuilder inBuilder = new StringBuilder();
        try (Reader reader = Files.newBufferedReader(filePath, charset)) {
            char[] buffer = new char[CHAR_BUFFER_SIZE];
            while (true) {
                int readCount = reader.read(buffer);
                if (readCount < 0)
                    break;
                inBuilder.append(buffer, 0, readCount);
            }
        }
        return inBuilder.toString();
    }
}
