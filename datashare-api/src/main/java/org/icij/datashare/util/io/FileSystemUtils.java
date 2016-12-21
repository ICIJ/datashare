package org.icij.datashare.util.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

/**
 * Created by julien on 4/22/16.
 */
public class FileSystemUtils {

    private static final Logger LOGGER = Logger.getLogger(FileSystemUtils.class.getName());


    public static final String FILE_SEP = File.separator;

    public static final Charset DEFAULT_ENCODING = StandardCharsets.UTF_8;

    public static final int CHAR_BUFFER_SIZE = 8192;


    public static List<Path> listAll(Path directory) throws IOException {
        return listFiles(directory, Collections.emptyList());
    }

    public static List<Path> listFiles(Path directory, List<String> filterFileExts) {
        List<Path> paths = new ArrayList<>();
        String exts = filterFileExts.isEmpty() ? "" : ".{" + String.join(",", filterFileExts) + "}";
        try ( DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + exts) ) {
            stream.forEach( paths::add );

        } catch (DirectoryIteratorException | IOException e) {
            LOGGER.log(INFO, "Failed to list directory " + directory, e);
            //throw new IOException("Failed to list directory " + directory + e.getMessage(), e.getCause());
        }
        return paths;
    }

    public static List<Path> listFiles(Path directory) {
        List<Path> paths = new ArrayList<>();
        try ( DirectoryStream<Path> stream = Files.newDirectoryStream(directory, p -> Files.isRegularFile(p)) ) {
            stream.forEach( paths::add );

        } catch (DirectoryIteratorException | IOException e) {
            LOGGER.log(INFO, "Failed to list directory " + directory, e);
        }
        return paths;
    }

    public static void writeToFile(Path filePath, Charset charset, String content, OpenOption... openOptions) throws IOException {
        try ( Writer writer = new OutputStreamWriter( Files.newOutputStream(filePath, openOptions), charset ) ) {
            writer.write(content);
        }
    }

    public static String readFromFile(Path filePath, Charset charset) throws IOException {
        StringBuilder inBuilder = new StringBuilder();
        try ( Reader reader = Files.newBufferedReader(filePath, charset) ) {
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
