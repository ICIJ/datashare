package org.icij.datashare.tasks;

import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.bus.amqp.TaskCreation;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

public class TestTaskUtils {
    public static void init(DatashareTaskFactoryForTest taskFactory) {
        reset(taskFactory);
        doReturn(mock(IndexTask.class)).when(taskFactory).createIndexTask(any(), any());
        doReturn(mock(ScanTask.class)).when(taskFactory).createScanTask(any(), any());
        doReturn(mock(DeduplicateTask.class)).when(taskFactory).createDeduplicateTask(any(), any());
        doReturn(mock(BatchDownloadRunner.class)).when(taskFactory).createBatchDownloadRunner(any(), any());
        doReturn(mock(BatchSearchRunner.class)).when(taskFactory).createBatchSearchRunner(any(), any());
        doReturn(mock(ScanIndexTask.class)).when(taskFactory).createScanIndexTask(any(), any());
        doReturn(mock(EnqueueFromIndexTask.class)).when(taskFactory).createEnqueueFromIndexTask(any(), any());
        doReturn(mock(ExtractNlpTask.class)).when(taskFactory).createExtractNlpTask(any(), any());
        doReturn(new TestTask(10)).when(taskFactory).createTestTask(any(Task.class), any(Function.class));
        doReturn(new TestSleepingTask(100000)).when(taskFactory).createTestSleepingTask(any(Task.class), any(Function.class));
        doReturn(mock(TaskCreation.class)).when(taskFactory).createTaskCreation(any(Task.class), any(Function.class));
    }

    public interface DatashareTaskFactoryForTest extends DatashareTaskFactory {
        TestSleepingTask createTestSleepingTask(Task<Integer> task, Function<Double, Void> updateCallback);
        TestTask createTestTask(Task<Integer> task, Function<Double, Void> updateCallback);
        TaskCreation createTaskCreation(Task<?> task, Function<Double, Void> updateCallback);
    }
}
