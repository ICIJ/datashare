package org.icij.datashare.tasks;

import junit.framework.TestCase;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT;

public class ScanTaskTest extends TestCase {
    private static final Path DOCS = Paths.get(ClassLoader.getSystemResource("docs").getPath());
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    public void test_scan() throws Exception {
        assertThat(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), DOCS.toString()).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:index", Path.class);
        assertThat(queue.size()).isEqualTo(3);
    }

    public void test_scan_with_queue_name() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new PropertiesProvider(Map.of(DATA_DIR_OPT, DOCS.toString())),
                new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, DOCS.toString(), QUEUE_NAME_OPT, "foo")), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("foo:index", Path.class);
        assertThat(queue.size()).isEqualTo(3);
    }

    public void test_scan_accepts_a_subdirectory_of_the_data_dir() throws Exception {
        assertThat(scanTask(Map.of(DATA_DIR_OPT, DOCS.getParent().toString()), DOCS.toString()).call()).isEqualTo(3);
    }

    public void test_scan_refuses_a_path_outside_the_data_dir() throws Exception {
        assertRefused(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), "/"));
    }

    public void test_scan_refuses_parent_traversal() throws Exception {
        assertRefused(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), DOCS + "/../.."));
    }

    public void test_scan_refuses_a_sibling_sharing_the_prefix() throws Exception {
        assertRefused(scanTask(Map.of(DATA_DIR_OPT, DOCS.toString()), DOCS + "-evil"));
    }

    public void test_scan_refuses_everything_else_when_no_data_dir_is_configured() throws Exception {
        assertRefused(scanTask(Map.of(), DOCS.toString()));
    }

    private ScanTask scanTask(Map<String, Object> serverProperties, String dataDirArg) {
        return new ScanTask(documentCollectionFactory, new PropertiesProvider(serverProperties),
                new Task<>("org.icij.datashare.tasks.ScanTask", User.local(), Map.of(DATA_DIR_OPT, dataDirArg)), null);
    }

    private void assertRefused(ScanTask task) throws Exception {
        try {
            task.call();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("dataDir must be inside");
        }
        assertThat(documentCollectionFactory.createQueue("extract:queue:index", Path.class).size()).isEqualTo(0);
    }
}
