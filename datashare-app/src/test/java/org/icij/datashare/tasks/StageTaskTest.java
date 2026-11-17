package org.icij.datashare.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.icij.datashare.PipelineHelper;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.asynctasks.Task;
import org.icij.datashare.asynctasks.TaskRepositoryMemory;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;

public class StageTaskTest {
    private final MemoryDocumentCollectionFactory<String> factory = new MemoryDocumentCollectionFactory<>();
    private final TaskRepositoryMemory taskRepository = new TaskRepositoryMemory();

    /** What a processor builds with the context: counts how many were built and whether it was closed. */
    private static class Resource implements AutoCloseable {
        boolean closed;

        @Override
        public void close() {closed = true;}
    }

    private static class UsingTheContext implements StageProcessor<String, Void> {
        final AtomicInteger built = new AtomicInteger();
        volatile Resource resource;

        @Override
        public Stage stage() {return Stage.LANGUAGE;}

        @Override
        public Class<String> itemType() {return String.class;}

        @Override
        public StageResult process(List<String> batch, StageContext<Void> ctx) {
            resource = ctx.resource(Resource.class, () -> {
                built.incrementAndGet();
                return new Resource();
            });
            return new StageResult(batch.size(), 0, 0, 0);
        }
    }

    /** Hands every entry it receives to the next stage, prefixed so that the two queues can be told apart. */
    private static class Emitting implements StageProcessor<String, String> {
        @Override
        public Stage stage() {return Stage.CATEGORIZE;}

        @Override
        public Class<String> itemType() {return String.class;}

        @Override
        public StageResult process(List<String> batch, StageContext<String> ctx) {
            ctx.emit(batch.stream().map(entry -> "next-" + entry).toList());
            return new StageResult(batch.size(), 0, 0, 0);
        }
    }

    @Test(timeout = 30000)
    public void test_what_a_processor_emits_goes_to_the_queue_of_the_next_stage() throws Exception {
        for (int i = 0; i < 4; i++) {
            factory.createQueue("extract:queue:categorize", String.class).add("doc" + i);
        }
        // the output factory is another instance, as in production: the queues of one type are kept apart
        MemoryDocumentCollectionFactory<String> outputFactory = new MemoryDocumentCollectionFactory<>();
        PropertiesProvider props = new PropertiesProvider(Map.of("defaultProject", "prj", "parallelism", "2"));
        Task<Long> task = new Task<>(LanguageDetectTask.class.getName(), User.local(), Map.of("defaultProject", "prj"));
        StageTask<String, String> stageTask = new StageTask<>(Stage.CATEGORIZE, User.local(), factory, props,
                String.class, new UpstreamGate.Factory(taskRepository).forTask(task),
                new StageRegistry().register(new Emitting()), outputFactory, String.class);

        stageTask.call();

        List<String> emitted = new ArrayList<>();
        outputFactory.createQueue(new PipelineHelper(props).getOutputQueueNameFor(Stage.CATEGORIZE), String.class)
                     .drainTo(emitted);
        assertThat(emitted).containsOnly("next-doc0", "next-doc1", "next-doc2", "next-doc3");
    }

    @Test(timeout = 30000)
    public void test_emitting_without_an_output_queue_fails_the_run() {
        factory.createQueue("extract:queue:categorize", String.class).add("doc");
        PropertiesProvider props = new PropertiesProvider(Map.of("defaultProject", "prj"));
        Task<Long> task = new Task<>(LanguageDetectTask.class.getName(), User.local(), Map.of("defaultProject", "prj"));
        StageTask<String, String> stageTask = new StageTask<>(Stage.CATEGORIZE, User.local(), factory, props,
                String.class, new UpstreamGate.Factory(taskRepository).forTask(task),
                new StageRegistry().register(new Emitting()));

        WorkersFailed failure = assertThrows(WorkersFailed.class, stageTask::call);

        assertThat(failure.getCause()).isInstanceOf(IllegalStateException.class);
    }

    @Test(timeout = 30000)
    public void test_a_resource_is_built_once_for_the_whole_run_and_closed_when_it_ends() throws Exception {
        DocumentQueue<String> queue = factory.createQueue("extract:queue:language", String.class);
        for (int i = 0; i < 5; i++) {
            queue.add("doc" + i);
        }
        UsingTheContext processor = new UsingTheContext();
        Task<Long> task = new Task<>(LanguageDetectTask.class.getName(), User.local(),
                                     Map.of("defaultProject", "prj", "parallelism", "2"));
        StageTask<String, Void> stageTask = new StageTask<>(Stage.LANGUAGE, User.local(), factory,
                                                            new PropertiesProvider(task.args), String.class,
                                                            new UpstreamGate.Factory(taskRepository).forTask(task),
                                                            new StageRegistry().register(processor));

        Long processed = stageTask.call();

        assertThat(processed).isEqualTo(5);
        assertThat(processor.built.get()).isEqualTo(1);
        assertThat(processor.resource.closed).isTrue();
    }
}
