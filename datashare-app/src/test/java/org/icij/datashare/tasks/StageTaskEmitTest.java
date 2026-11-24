package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Stage;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageProcessor;
import org.icij.datashare.asynctasks.StageRegistry;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.datashare.user.User;
import org.icij.extract.queue.DocumentQueue;
import org.junit.Test;
import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;

/** What happens to what a stage emits: it waits for room in the next stage's queue, and goes nowhere at the end. */
public class StageTaskEmitTest {
    private static final int NB_PATHS = 20;
    // a queue of 2: a source that emits more than that in one call needs the consumer to make room
    private final MemoryDocumentCollectionFactory<Path> factory =
            new MemoryDocumentCollectionFactory<>(new PropertiesProvider(Map.of("queueCapacity", "2")));

    @Test(timeout = 20000)
    public void test_a_full_next_stage_queue_slows_the_emitter_down_instead_of_losing_what_it_emits() throws Exception {
        StageTask<Void, Path> task = sourceTask(factory, Map.of("stages", "SCAN,INDEX"));
        List<Path> consumed = new CopyOnWriteArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        // a slower consumer than the producer: the queue (capacity 2) is full most of the time
        Thread consumer = new Thread(() -> {
            DocumentQueue<Path> queue = factory.createQueue("extract:queue:index", Path.class);
            try {
                while (consumed.size() < NB_PATHS && !done.get()) {
                    Path path = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (path != null) {
                        consumed.add(path);
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        consumer.start();

        Long emitted = task.call();
        consumer.join(10000);
        done.set(true);

        assertThat(emitted).isEqualTo(NB_PATHS);
        assertThat(consumed).hasSize(NB_PATHS);
    }

    @Test(timeout = 10000)
    public void test_the_last_stage_of_the_run_with_an_in_memory_queue_emits_nothing_and_does_not_block()
            throws Exception {
        // nothing can ever drain a memory queue after the last stage: filling it would block the stage at its capacity
        Long emitted = sourceTask(factory, Map.of("stages", "SCAN")).call();

        assertThat(emitted).isEqualTo(NB_PATHS);
        assertThat(factory.createQueue("extract:queue:index", Path.class).size()).isEqualTo(0);
    }

    @Test(timeout = 10000)
    public void test_a_stage_that_is_not_the_last_of_the_run_keeps_filling_the_next_stage_queue() throws Exception {
        MemoryDocumentCollectionFactory<Path> roomy = new MemoryDocumentCollectionFactory<>();

        sourceTask(roomy, Map.of("stages", "SCAN,INDEX")).call();

        assertThat(roomy.createQueue("extract:queue:index", Path.class).size()).isEqualTo(NB_PATHS);
    }

    @Test(timeout = 10000)
    public void test_a_stage_that_is_not_in_the_stages_of_the_run_keeps_filling_its_queue() throws Exception {
        // started on its own, as the web path does, with a consumer started separately: not known to be the last
        MemoryDocumentCollectionFactory<Path> roomy = new MemoryDocumentCollectionFactory<>();

        sourceTask(roomy, Map.of("stages", "INDEX")).call();

        assertThat(roomy.createQueue("extract:queue:index", Path.class).size()).isEqualTo(NB_PATHS);
    }

    private StageTask<Void, Path> sourceTask(MemoryDocumentCollectionFactory<Path> outputFactory,
                                             Map<String, Object> properties) {
        return new StageTask<>(Stage.SCAN, User.local(), "taskId", null, new PropertiesProvider(properties), Void.class,
                               UpstreamGate.NONE, new StageRegistry().register(source), outputFactory, Path.class);
    }

    private final StageProcessor<Void, Path> source = new StageProcessor<>() {
        public Stage stage() {return Stage.SCAN;}

        public Class<Void> itemType() {return Void.class;}

        public StageResult process(List<Void> batch, StageContext<Path> ctx) {
            List<Path> paths = IntStream.range(0, NB_PATHS).mapToObj(i -> get("/path/to/doc" + i)).toList();
            ctx.emit(paths);
            return new StageResult(paths.size(), 0, 0, 0);
        }
    };
}
