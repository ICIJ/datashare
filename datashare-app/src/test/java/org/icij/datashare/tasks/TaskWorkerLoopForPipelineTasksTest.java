package org.icij.datashare.tasks;

import java.util.List;
import java.util.function.Function;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extension.PipelineRegistry;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.icij.datashare.user.User;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class TaskWorkerLoopForPipelineTasksTest {
    @Mock
    DatashareTaskFactory taskFactory;
    @Mock Function<Double, Void> updateCallback;
    @Mock ElasticsearchSpewer spewer;
    private final TaskManagerMemory taskSupplier = new TaskManagerMemory(taskFactory, new TaskRepositoryMemory(), new PropertiesProvider());

    @Test
    public void test_scan_task() throws Exception {
        Task task = new Task(ScanTask.class.getName(), User.local(), Map.of("dataDir", "/path/to/files"));
        ScanTask taskRunner = new ScanTask(mock(DocumentCollectionFactory.class), task, updateCallback);
        when(taskFactory.createScanTask(any(), any())).thenReturn(taskRunner);

        testTaskWithTaskRunner(task);
    }

    @Test
    public void test_index_task() throws Exception {
        Task task = new Task(IndexTask.class.getName(), User.local(), new HashMap<>());
        IndexTask taskRunner = new IndexTask(spewer, mock(DocumentCollectionFactory.class), task, updateCallback);
        when(taskFactory.createIndexTask(any(), any())).thenReturn(taskRunner);

        testTaskWithTaskRunner(task);
    }

    @Test
    public void test_extract_nlp_task() throws Exception {
        Task task = new Task(ExtractNlpTask.class.getName(), User.local(), Map.of("nlpPipeline", "EMAIL"));
        ExtractNlpTask taskRunner = new ExtractNlpTask(mock(Indexer.class), new PipelineRegistry(new PropertiesProvider()), mock(DocumentCollectionFactory.class), task, updateCallback);
        when(taskFactory.createExtractNlpTask(any(), any())).thenReturn(taskRunner);

        testTaskWithTaskRunner(task);
    }

    @Test
    public void test_scan_index_task() throws Exception {
        Task task = new Task(ScanIndexTask.class.getName(), User.local(), new HashMap<>());
        ScanIndexTask taskRunner = new ScanIndexTask(mock(DocumentCollectionFactory.class), mock(Indexer.class), task, updateCallback);
        when(taskFactory.createScanIndexTask(any(), any())).thenReturn(taskRunner);

        testTaskWithTaskRunner(task);
    }

    @Test
    public void test_enqueue_from_index_task() throws Exception {
        Task task = new Task(EnqueueFromIndexTask.class.getName(), User.local(), Map.of("nlpPipeline", "EMAIL"));
        EnqueueFromIndexTask taskRunner = new EnqueueFromIndexTask(mock(DocumentCollectionFactory.class), mock(Indexer.class), task, updateCallback);
        when(taskFactory.createEnqueueFromIndexTask(any(), any())).thenReturn(taskRunner);

        testTaskWithTaskRunner(task);
    }

    @Test
    public void test_deduplicate_task() throws Exception {
        Task task = new Task(DeduplicateTask.class.getName(), User.local(), new HashMap<>());
        DeduplicateTask taskRunner = new DeduplicateTask(mock(DocumentCollectionFactory.class), task, updateCallback);
        when(taskFactory.createDeduplicateTask(any(), any())).thenReturn(taskRunner);

        testTaskWithTaskRunner(task);
    }

    private void testTaskWithTaskRunner(Task task) throws Exception {
        taskSupplier.startTask(task.name, User.local(), task.args);
        taskSupplier.awaitTermination(1, TimeUnit.SECONDS);

        List<Task> tasks = taskSupplier.getTasks().toList();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).name).isEqualTo(task.name);
    }

    @Before
    public void setUp() {
        initMocks(this);
        when(spewer.configure(any())).thenReturn(spewer);
    }
}
