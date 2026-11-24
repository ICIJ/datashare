package org.icij.datashare.tasks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.icij.datashare.asynctasks.DefaultStageContext;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Project;
import org.junit.Test;
import static java.nio.file.Paths.get;
import static org.fest.assertions.Assertions.assertThat;

public class DeduplicateStageTest {
    private final DeduplicateStage stage = new DeduplicateStage();
    private final List<Path> emitted = new ArrayList<>();
    private final ScopedStageContext<Path> ctx = new DefaultStageContext<>("taskId", Project.project("prj"), Map.of(), out -> emitted.addAll(out));

    @Test
    public void test_an_empty_batch_emits_nothing() {
        assertThat(stage.process(List.of(), ctx)).isEqualTo(StageResult.EMPTY);
        assertThat(emitted).isEmpty();
    }

    @Test
    public void test_a_path_is_emitted_once_and_the_duplicates_are_counted() {
        StageResult result = stage.process(List.of(get("/a"), get("/b"), get("/a")), ctx);

        assertThat(emitted).containsExactly(get("/a"), get("/b"));
        assertThat(result).isEqualTo(new StageResult(1, 0, 0, 0));
    }

    @Test
    public void test_the_paths_seen_are_kept_across_the_batches_of_a_context() {
        stage.process(List.of(get("/a")), ctx);
        StageResult second = stage.process(List.of(get("/a"), get("/c")), ctx);

        assertThat(emitted).containsExactly(get("/a"), get("/c"));
        assertThat(second).isEqualTo(new StageResult(1, 0, 0, 0));
    }

    @Test
    public void test_a_closed_context_starts_again_without_what_was_seen() {
        stage.process(List.of(get("/a")), ctx);
        ctx.close();
        StageResult result = stage.process(List.of(get("/a")), ctx);

        assertThat(emitted).containsExactly(get("/a"), get("/a"));
        assertThat(result).isEqualTo(StageResult.EMPTY);
    }
}
