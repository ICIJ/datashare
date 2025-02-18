package org.icij.datashare.tasks;

import junit.framework.TestCase;
import org.icij.datashare.asynctasks.Group;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskGroupType;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.cli.DatashareCliOptions.DATA_DIR_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.QUEUE_NAME_OPT;

public class ScanTaskTest extends TestCase {
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    public void test_scan() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                new Group(TaskGroupType.Test), Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString())), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:index", Path.class);
        assertThat(queue.size()).isEqualTo(4); // with POISON
    }

    public void test_scan_with_queue_name() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                new Group(TaskGroupType.Test), Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString(),
                        QUEUE_NAME_OPT, "foo")), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("foo:index", Path.class);
        assertThat(queue.size()).isEqualTo(4); // with POISON
    }
}