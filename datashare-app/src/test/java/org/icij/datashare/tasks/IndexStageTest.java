package org.icij.datashare.tasks;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.extract.MemoryDocumentCollectionFactory;
import org.icij.extract.extractor.ExtractionStatus;
import org.icij.extract.report.Report;
import org.icij.extract.report.Reporter;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ElasticsearchSpewer;
import org.junit.Before;
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
import static org.mockito.Mockito.when;

public class IndexStageTest {
    private final Indexer indexer = mock(Indexer.class);
    private final Extractor extractor = mock(Extractor.class);
    private final AtomicInteger extractorsBuilt = new AtomicInteger();
    private final IndexStage stage = new IndexStage(indexer, text -> Language.ENGLISH, (documentFactory, options) -> {
        extractorsBuilt.incrementAndGet();
        return extractor;
    });
    private boolean cancelled;

    @Before
    public void succeedByDefault() throws Exception {
        when(extractor.extract(any(Path.class), any(ElasticsearchSpewer.class))).thenReturn(ExtractionStatus.SUCCESS);
        when(extractor.extract(any(Path.class), any(ElasticsearchSpewer.class), any(Reporter.class)))
                .thenReturn(ExtractionStatus.SUCCESS);
    }
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

    private ScopedStageContext<String> ctxWithReport(String reportName) {
        return new ScopedStageContext<>() {
            public Project project() {return Project.project("prj");}

            public Map<String, Object> options() {return Map.of("defaultProject", "prj", "reportName", reportName);}

            public boolean isCancelled() {return false;}

            public void emit(List<String> out) {}
        };
    }

    private IndexStage stageWithMap(MemoryDocumentCollectionFactory<Path> maps) {
        return new IndexStage(indexer, text -> Language.ENGLISH, (documentFactory, options) -> extractor,
                              maps::createMap);
    }

    @Test
    public void test_a_path_recorded_in_the_report_map_is_skipped_and_counted_unchanged() throws Exception {
        MemoryDocumentCollectionFactory<Path> maps = new MemoryDocumentCollectionFactory<>();
        Path done = Paths.get("done.txt");
        maps.createMap("report").putAll(Map.of(done, new Report(ExtractionStatus.SUCCESS)));

        when(extractor.extract(eq(done), any(ElasticsearchSpewer.class), any(Reporter.class)))
                .thenReturn(ExtractionStatus.SKIPPED);

        StageResult result = stageWithMap(maps).process(List.of(done, Paths.get("new.txt")), ctxWithReport("report"));

        assertThat(result).isEqualTo(new StageResult(1, 1, 0, 0));
        assertThat(maps.createMap("report").get(done)).isEqualTo(new Report(ExtractionStatus.SUCCESS));
    }

    @Test
    public void test_without_a_report_name_the_report_map_is_not_used() throws Exception {
        MemoryDocumentCollectionFactory<Path> maps = new MemoryDocumentCollectionFactory<>();

        stageWithMap(maps).process(List.of(Paths.get("a.txt")), ctx);

        verify(extractor).extract(eq(Paths.get("a.txt")), any(ElasticsearchSpewer.class));
    }

    @Test
    public void test_a_report_name_without_a_map_source_is_ignored() throws Exception {
        StageResult result = stage.process(List.of(Paths.get("a.txt")), ctxWithReport("report"));

        assertThat(result).isEqualTo(new StageResult(1, 0, 0, 0));
        verify(extractor).extract(eq(Paths.get("a.txt")), any(ElasticsearchSpewer.class));
    }

    @Test
    public void test_what_is_extracted_is_recorded_in_the_report_map_and_skipped_on_the_next_run() throws Exception {
        MemoryDocumentCollectionFactory<Path> maps = new MemoryDocumentCollectionFactory<>();
        IndexStage realExtraction = new IndexStage(indexer, text -> Language.ENGLISH, Extractor::new, maps::createMap);
        Path file = Paths.get(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());

        ScopedStageContext<String> firstRun = ctxWithReport("report");
        StageResult first = realExtraction.process(List.of(file), firstRun);
        firstRun.close();
        StageResult second = realExtraction.process(List.of(file), ctxWithReport("report"));

        assertThat(first).isEqualTo(new StageResult(1, 0, 0, 0));
        assertThat(maps.createMap("report").get(file).getStatus()).isEqualTo(ExtractionStatus.SUCCESS);
        assertThat(second).isEqualTo(new StageResult(0, 1, 0, 0));
    }

    @Test
    public void test_a_failed_extraction_is_recorded_in_the_report_map_and_not_thrown() throws Exception {
        MemoryDocumentCollectionFactory<Path> maps = new MemoryDocumentCollectionFactory<>();
        IndexStage realExtraction = new IndexStage(indexer, text -> Language.ENGLISH, Extractor::new, maps::createMap);
        Path missing = Paths.get("/does/not/exist.txt");

        ScopedStageContext<String> run = ctxWithReport("report");
        StageResult result = realExtraction.process(List.of(missing), run);
        run.close();

        // extract-lib records the failure in the map instead of throwing it, and returns its status
        assertThat(maps.createMap("report").get(missing).getStatus()).isNotEqualTo(ExtractionStatus.SUCCESS);
        assertThat(result).isEqualTo(new StageResult(0, 0, 0, 1));
    }
}
