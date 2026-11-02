package org.icij.datashare.tasks;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public class ExtractNlpTaskTest {
    private final AbstractPipeline pipeline = mock(AbstractPipeline.class);
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private final ExtractNlpTask nlpTask = new ExtractNlpTask(factory, new StageRegistry().register(
            new NlpStage(mock(Indexer.class), pipeline)), new UpstreamGate.Factory(new TaskRepositoryMemory()),
            new Task<>(ExtractNlpTask.class.getName(), User.local(), Map.of()), null);

    @Test(timeout = 2000)
    public void test_returns_immediately_when_queue_is_empty() throws Exception {
        assertThat(nlpTask.call()).isEqualTo(0L);
    }
}
