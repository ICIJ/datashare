package org.icij.datashare.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.FatalStageException;
import org.icij.datashare.asynctasks.DefaultStageContext;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Project;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.assertThrows;

public class StageItemsTest {
    private static final StageResult UPDATED = new StageResult(1, 0, 0, 0);
    private static final StageResult FAILED = new StageResult(0, 0, 0, 1);
    private final Logger logger = LoggerFactory.getLogger(StageItemsTest.class);
    private final StageContext<Void> ctx = new DefaultStageContext<>("taskId", Project.project("prj"), Map.of(), out -> {});
    private final List<String> seen = new ArrayList<>();

    @After
    public void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    public void test_what_each_item_did_is_summed() {
        StageResult result = StageItems.forEach(logger, ctx, List.of("a", "b", "c"), item -> {
            seen.add(item);
            return item.equals("b") ? new StageResult(0, 1, 0, 0) : UPDATED;
        });

        assertThat(result).isEqualTo(new StageResult(2, 1, 0, 0));
        assertThat(seen).containsExactly("a", "b", "c");
    }

    @Test
    public void test_an_exception_is_one_failed_item_and_the_batch_goes_on() {
        StageResult result = StageItems.forEach(logger, ctx, List.of("a", "bad", "c"), item -> {
            if (item.equals("bad")) {
                throw new IllegalStateException("boom");
            }
            return UPDATED;
        });

        assertThat(result).isEqualTo(new StageResult(2, 0, 0, 1));
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    public void test_a_fatal_exception_stops_the_run() {
        FatalStageException fatal = new FatalStageException("bad configuration");

        assertThat(assertThrows(FatalStageException.class, () -> StageItems.forEach(logger, ctx, List.of("a", "b"), item -> {
            seen.add(item);
            throw fatal;
        }))).isSameAs(fatal);
        assertThat(seen).containsExactly("a");
    }

    @Test
    public void test_an_error_is_not_caught() {
        assertThrows(OutOfMemoryError.class, () -> StageItems.forEach(logger, ctx, List.of("a"), item -> {
            throw new OutOfMemoryError();
        }));
    }

    @Test
    public void test_an_interruption_ends_the_batch_counts_nothing_and_leaves_the_flag_set() {
        StageResult result = StageItems.forEach(logger, ctx, List.of("a", "b", "c"), item -> {
            seen.add(item);
            if (item.equals("b")) {
                throw new InterruptedException();
            }
            return UPDATED;
        });

        assertThat(result).isEqualTo(UPDATED);
        assertThat(seen).containsExactly("a", "b");
        assertThat(ctx.isCancelled()).isTrue();
    }

    @Test
    public void test_an_interruption_wrapped_by_a_client_that_interrupted_the_thread_is_a_cancellation() {
        StageResult result = StageItems.forEach(logger, ctx, List.of("a", "b"), item -> {
            seen.add(item);
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException());
        });

        assertThat(result).isEqualTo(StageResult.EMPTY);
        assertThat(seen).containsExactly("a");
    }

    @Test
    public void test_an_interruption_wrapped_on_a_thread_that_was_not_interrupted_is_a_failed_item() {
        StageResult result = StageItems.forEach(logger, ctx, List.of("a", "b"), item -> {
            seen.add(item);
            throw new RuntimeException(new InterruptedException());
        });

        assertThat(result).isEqualTo(new StageResult(0, 0, 0, 2));
        assertThat(seen).containsExactly("a", "b");
    }

    @Test
    public void test_a_run_already_cancelled_processes_nothing() {
        Thread.currentThread().interrupt();

        StageResult result = StageItems.forEach(logger, ctx, List.of("a"), item -> {
            seen.add(item);
            return UPDATED;
        });

        assertThat(result).isEqualTo(StageResult.EMPTY);
        assertThat(seen).isEmpty();
    }
}
