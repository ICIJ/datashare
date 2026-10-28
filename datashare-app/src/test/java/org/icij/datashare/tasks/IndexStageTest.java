package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.junit.Test;
import org.icij.extract.extractor.Extractor;
import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class IndexStageTest {
    private final Indexer indexer = mock(Indexer.class);
    private final Extractor extractor = mock(Extractor.class);
    private final AtomicInteger extractorsBuilt = new AtomicInteger();
    private final IndexStage stage = new IndexStage(indexer, text -> Language.ENGLISH, (documentFactory, options) -> {
        extractorsBuilt.incrementAndGet();
        return extractor;
    });
    private boolean cancelled;
    private final ScopedStageContext<String> ctx = new ScopedStageContext<>() {
        public Project project() {return Project.project("prj");}

        public Map<String, Object> options() {return Map.of("defaultProject", "prj");}

        public boolean isCancelled() {return cancelled;}

        public void emit(List<String> out) {}
    };

    @Test
    public void test_a_failing_index_creation_is_unchecked_and_the_extractor_is_still_closed_with_the_context()
            throws Exception {
        IOException failure = new IOException("elasticsearch is down");
        doThrow(failure).when(indexer).createIndex("prj");

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                                                   () -> stage.process(List.of(Paths.get("a.txt")), ctx));

        assertThat(thrown.getCause()).isSameAs(failure);
        ctx.close();
        verify(extractor).close();
    }

    @Test
    public void test_a_failing_extractor_factory_is_rethrown() {
        IndexStage failing = new IndexStage(indexer, text -> Language.ENGLISH, (documentFactory, options) -> {
            throw new IllegalStateException("no extractor");
        });

        assertThrows(IllegalStateException.class, () -> failing.process(List.of(Paths.get("a.txt")), ctx));
    }

    @Test
    public void test_counts_what_was_indexed_and_what_failed() throws Exception {
        Path bad = Paths.get("bad.txt");
        doThrow(new IOException("unreadable")).when(extractor).extract(eq(bad), any(ElasticsearchSpewer.class));

        StageResult result = stage.process(List.of(Paths.get("a.txt"), bad, Paths.get("b.txt")), ctx);

        assertThat(result).isEqualTo(new StageResult(2, 0, 0, 1));
    }

    @Test
    public void test_a_cancelled_run_extracts_nothing() throws Exception {
        cancelled = true;

        StageResult result = stage.process(List.of(Paths.get("a.txt")), ctx);

        assertThat(result).isEqualTo(StageResult.EMPTY);
        verify(extractor, never()).extract(any(Path.class), any(ElasticsearchSpewer.class));
    }

    @Test
    public void test_the_extractor_and_the_index_are_built_once_for_a_context() throws Exception {
        stage.process(List.of(Paths.get("a.txt")), ctx);
        stage.process(List.of(Paths.get("b.txt")), ctx);

        assertThat(extractorsBuilt.get()).isEqualTo(1);
        verify(indexer, times(1)).createIndex("prj");
    }

    @Test
    public void test_a_closed_context_starts_again_with_its_own_extractor() throws Exception {
        stage.process(List.of(Paths.get("a.txt")), ctx);
        ctx.close();
        stage.process(List.of(Paths.get("b.txt")), ctx);
        ctx.close();

        assertThat(extractorsBuilt.get()).isEqualTo(2);
        verify(extractor, times(2)).close();
    }
}
