package org.icij.datashare.text.extraction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

import org.icij.datashare.concurrent.task.Task;
import org.icij.datashare.concurrent.task.AbstractTask;
import org.icij.datashare.concurrent.queue.OutputQueue;
import org.icij.datashare.concurrent.Latch;
import org.icij.datashare.concurrent.BooleanLatch;
import static org.icij.datashare.util.io.FileSystemUtils.listFiles;


/**
 * Task consisting in an elementary non-recursive listing of a directory
 *
 * Created by julien on 10/14/16.
 */
public class FileSystemScanningTask extends AbstractTask implements OutputQueue<Path> {

    private final Path root;

    private final BlockingQueue<Path> output;

    private Latch noMoreOutput;


    public FileSystemScanningTask(Path path, BlockingQueue<Path> output) {
        this.root = Files.isDirectory(path) ? path : path.getParent();
        this.output = output;
        this.noMoreOutput = new BooleanLatch();
    }


    @Override
    public Task.Result call() {
        for (Path path : listFiles(root).stream().limit(3).collect(Collectors.toList())) {
            LOGGER.info(getClass().getName() + " - " + "Putting " + path + " on queue " + output());
            put(path);
        }
        noMoreOutput.signal();
        return Task.Result.SUCCESS;
    }

    @Override
    public BlockingQueue<Path> output() {
        return output;
    }

    @Override
    public Latch noMoreOutput() {
        return noMoreOutput;
    }

}
