package org.icij.datashare.tasks;

import junit.framework.TestCase;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.PropertiesProvider.DATA_DIR_OPT;
import static org.icij.datashare.PropertiesProvider.QUEUE_NAME_OPT;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ScanTaskTest extends TestCase {
    private final MemoryDocumentCollectionFactory<Path> documentCollectionFactory = new MemoryDocumentCollectionFactory<>();

    public void test_scan() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString())), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("extract:queue:index", Path.class);
        assertThat(queue.size()).isEqualTo(3);
    }

    public void test_scan_cancelled_is_cancelled_instead_of_done() throws Exception {
        ScanTask scanTask = new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString())), null);
        scanTask.cancel(false);
        Thread.currentThread().interrupt();
        try {
            scanTask.call();
            fail("expected an InterruptedException");
        } catch (InterruptedException expected) {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    public void test_scan_interrupted_without_cancel_is_not_cancelled() throws Exception {
        ScanTask scanTask = new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString())), null);
        Thread.currentThread().interrupt();
        try {
            assertThat(scanTask.call()).isEqualTo(0);
        } finally {
            Thread.interrupted();
        }
    }

    public void test_scan_cancelled_while_offering_to_a_wrapping_queue_is_cancelled() throws Exception {
        DocumentCollectionFactory<Path> factory = mock(DocumentCollectionFactory.class);
        DocumentQueue<Path> queue = mock(DocumentQueue.class);
        when(factory.createQueue(anyString(), eq(Path.class))).thenReturn(queue);
        when(queue.offer(any(Path.class), anyLong(), any(TimeUnit.class))).thenThrow(new RuntimeException(new InterruptedException()));
        ScanTask scanTask = new ScanTask(factory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString())), null);
        scanTask.cancel(false);
        try {
            scanTask.call();
            fail("expected an InterruptedException");
        } catch (InterruptedException expected) {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    public void test_scan_with_queue_name() throws Exception {
        assertThat(new ScanTask(documentCollectionFactory, new Task<>("org.icij.datashare.tasks.ScanTask", User.local(),
                Map.of(DATA_DIR_OPT, Paths.get(ClassLoader.getSystemResource("docs").getPath()).toString(),
                        QUEUE_NAME_OPT, "foo")), null).call()).isEqualTo(3);
        DocumentQueue<Path> queue = documentCollectionFactory.createQueue("foo:index", Path.class);
        assertThat(queue.size()).isEqualTo(3);
    }
}