package org.icij.datashare.tasks;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCli;
import org.icij.datashare.user.User;
import org.icij.extract.redis.RedisDocumentQueue;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;

import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.user.User.nullUser;

public class PipelineTaskTest {
    private PropertiesProvider options = new PropertiesProvider(new HashMap<String, String>() {{
        put("redisAddress", "redis://redis:6379");
    }});
    TestPipelineTask task = new TestPipelineTask(DatashareCli.Stage.FILTER, nullUser(), options);
    RedisDocumentQueue outputQueue = new RedisDocumentQueue(task.getOutputQueueName(), "redis://redis:6379");

    @Test
    public void test_pipeline_task_transfer_to_output_queue() throws InterruptedException {
        task.queue.put(get("/path/to/doc1"));
        task.queue.put(get("/path/to/doc2"));

        task.transferToOutputQueue();

        assertThat(outputQueue.size()).isEqualTo(2);
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc1");
        assertThat(outputQueue.poll().toString()).isEqualTo("/path/to/doc2");
    }

    static class TestPipelineTask extends PipelineTask {
        public TestPipelineTask(DatashareCli.Stage stage, User user, PropertiesProvider propertiesProvider) {
            super(stage, user, propertiesProvider);
        }
        @Override public Long call() { return 0L;}
    }
    @After
    public void tearDown() throws IOException {
        task.queue.delete();
        outputQueue.delete();
    }
}
