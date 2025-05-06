package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.bus.amqp.TaskCreation;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

public class TestTaskUtils {
    public static void init(DatashareTaskFactoryForTest taskFactory) {
        reset(taskFactory);
        when(taskFactory.createIndexTask(any(), any())).thenReturn(mock(IndexTask.class));
        when(taskFactory.createScanTask(any(), any())).thenReturn(mock(ScanTask.class));
        when(taskFactory.createDeduplicateTask(any(), any())).thenReturn(mock(DeduplicateTask.class));
        when(taskFactory.createBatchDownloadRunner(any(), any())).thenReturn(mock(BatchDownloadRunner.class));
        when(taskFactory.createBatchSearchRunner(any(), any())).thenReturn(mock(BatchSearchRunner.class));
        when(taskFactory.createScanIndexTask(any(), any())).thenReturn(mock(ScanIndexTask.class));
        when(taskFactory.createEnqueueFromIndexTask(any(), any())).thenReturn(mock(EnqueueFromIndexTask.class));
        when(taskFactory.createExtractNlpTask(any(), any())).thenReturn(mock(ExtractNlpTask.class));
        when(taskFactory.createTestTask(any(Task.class), any(Function.class))).thenReturn(new TestTask(10));
        when(taskFactory.createTestSleepingTask(any(Task.class), any(Function.class))).thenReturn(new TestSleepingTask(100000));
        when(taskFactory.createTaskCreation(any(Task.class), any(Function.class))).thenReturn(mock(TaskCreation.class));
        when(taskFactory.createSerializationTestTask(any(Task.class), any(Function.class))).thenReturn(new SerializationTestTask(10));
    }

    public interface DatashareTaskFactoryForTest extends DatashareTaskFactory {
        TestSleepingTask createTestSleepingTask(Task task, Function<Double, Void> updateCallback);
        TestTask createTestTask(Task task, Function<Double, Void> updateCallback);
        TaskCreation createTaskCreation(Task task, Function<Double, Void> updateCallback);
        SerializationTestTask createSerializationTestTask(Task task, Function<Double, Void> updateCallback);
    }
}
