package org.icij.datashare.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import org.icij.datashare.concurrent.task.QueueOutTask;
import org.icij.datashare.concurrent.task.Task;
import org.icij.datashare.text.SourcePath;


/**
 * Task consisting in an elementary non-recursive listing of a directory
 *
 * Created by julien on 10/14/16.
 */
public class FileSystemScanning extends QueueOutTask<SourcePath> {

    public static FileSystemScanning create(Path path) {
        return new FileSystemScanning(path, new LinkedBlockingQueue<>());
    }


    private final Path root;


    private FileSystemScanning(Path path, BlockingQueue<SourcePath> output) {
        super(output);
        this.root = Files.isDirectory(path) ? path : path.getParent();
    }


    @Override
    public Task.Result call() {
        LOGGER.info(getClass().getName() + " - SCANNING FILES in " + root );
        for (Path path : FileSystemUtils.listFiles(root)) {
            LOGGER.info(getClass().getName() + " - PUTTING " + path + "["+output().size()+"]");
            Optional<SourcePath> sourcePath = SourcePath.create(path);
            if (sourcePath.isPresent())
                put(sourcePath.get());
            else {
                return Result.FAILURE;
            }
        }
        noMoreOutput().signal();
        return Task.Result.SUCCESS;
    }

}
