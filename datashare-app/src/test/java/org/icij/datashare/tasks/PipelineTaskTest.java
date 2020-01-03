package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;

import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.tasks.PipelineTask.POISON;
import static org.icij.datashare.user.User.nullUser;

public class PipelineTaskTest {
    private PropertiesProvider options = new PropertiesProvider(new HashMap<String, String>() {{
        put("queueName", "test:queue");
    }});
    DocumentCollectionFactory docCollectionFactory = new MemoryDocumentCollectionFactory();
    TestPipelineTask task = new TestPipelineTask(DatashareCli.Stage.FILTER, nullUser(), options);

    @Test
    public void test_pipeline_task_transfer_to_output_queue() throws Exception {
        task.queue.put(get("/path/to/doc1"));
        task.queue.put(get("/path/to/doc2"));

        task.transferToOutputQueue();

        assertThat(task.queue.isEmpty()).isTrue();
        DocumentQueue outputQueue = docCollectionFactory.createQueue(options, task.getOutputQueueName());
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
    }

    @Test
    public void test_pipeline_task_conditional_transfer_to_output_queue() throws Exception {
        task.queue.put(get("/path/to/doc1"));
        task.queue.put(get("/path/to/doc2"));
        task.queue.put(POISON);

        task.transferToOutputQueue(p -> p.toString().contains("1"));

        assertThat(task.queue.isEmpty()).isTrue();
        DocumentQueue outputQueue = docCollectionFactory.createQueue(options, task.getOutputQueueName());
        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo(POISON.toString());
    }

    class TestPipelineTask extends PipelineTask {
        public TestPipelineTask(DatashareCli.Stage stage, User user, PropertiesProvider propertiesProvider) {
            super(stage, user, docCollectionFactory, propertiesProvider);
        }
        @Override public Long call() { return 0L;}
    }
}
