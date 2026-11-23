package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import junit.framework.TestCase;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.PropertiesProvider;
import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.datashare.utils.DataDirVerifier;
import org.icij.extract.queue.DocumentQueue;

public class ScanTaskTest extends TestCase {
    private static final Path DOCS = Paths.get(ClassLoader.getSystemResource("docs").getPath());
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    public void test_scan() throws Exception {
        try (DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:index", Path.class)) {
            assertThat(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), DOCS.toString()).call()).isEqualTo(3);
            assertThat(queue.size()).isEqualTo(3);
        }
    }

    public void test_scan_with_queue_name() throws Exception {
        try (DocumentQueue<Path> queue = documentCollectionFactory.createQueue("foo:index", Path.class)) {
            assertThat(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), Map.of(DATA_DIR_OPT, DOCS.toString(), QUEUE_NAME_OPT, "foo")).call()).isEqualTo(3);
            assertThat(queue.size()).isEqualTo(3);
        }
    }

    public void test_scan_accepts_a_subdirectory_of_the_data_dir() throws Exception {
        assertThat(scanTask(Map.of(DATA_DIR_OPT, DOCS.getParent().toString()), DOCS.toString()).call()).isEqualTo(3);
    }

    public void test_scan_refuses_a_task_without_data_dir_when_called_not_when_built() throws Exception {
        assertIllegalArgument(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), Map.of()));
    }

    private ScanTask scanTask(Map<String, Object> serverProperties, String dataDirArg) {
        return scanTask(serverProperties, Map.of(DATA_DIR_OPT, dataDirArg));
    }

    private ScanTask scanTask(Map<String, Object> serverProperties, Map<String, Object> taskArgs) {
        return new ScanTask(documentCollectionFactory, new StageRegistry().register(new ScanStage(DataDirVerifier.from(new PropertiesProvider(serverProperties)))),
                new Task<>("org.icij.datashare.tasks.ScanTask", User.local(), taskArgs), null);
    }

    private void assertIllegalArgument(ScanTask task) throws Exception {
        try {
            task.call();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("must be");
        }
        assertThat(documentCollectionFactory.createQueue("extract:queue:index", Path.class).size()).isEqualTo(0);
    }
}
