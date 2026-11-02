package org.icij.datashare.tasks;

import java.util.List;
import java.util.Map;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Language;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.nlp.AbstractPipeline;
import org.junit.Test;
import static java.util.Collections.emptyList;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Language.ENGLISH;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class NlpStageTest {
    private final Indexer indexer = mock(Indexer.class);
    private final AbstractPipeline pipeline = mock(AbstractPipeline.class);
    private final NlpStage stage = new NlpStage(indexer, pipeline);
    private final ScopedStageContext<Void> ctx = new ScopedStageContext<>() {
        public Project project() {return Project.project("projectName");}

        public Map<String, Object> options() {return Map.of("defaultProject", "projectName", "maxContentLength", "32");}

        public boolean isCancelled() {return false;}

        public void emit(List<Void> out) {}
    };

    @Test
    public void test_does_nothing__when_doc_not_found_in_index() throws Exception {
        stage.process(List.of("unknownId"), ctx);

        verify(pipeline, never()).initialize(any(Language.class));
        verify(pipeline, never()).process(any());
    }

    @Test
    public void test_do_not_process_NLP__when_init_fails() throws Exception {
        when(pipeline.initialize(any())).thenReturn(false);
        Document doc = createDoc("content").build();
        when(indexer.get("projectName", DocReference.parse(doc.getId()))).thenReturn(doc);

        stage.process(List.of(doc.getId()), ctx);

        verify(pipeline, never()).process(any());
    }

    @Test
    public void test_process_NLP__when_doc_found_in_index() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("content").build();
        when(pipeline.process(doc)).thenReturn(emptyList());
        when(indexer.get("projectName", DocReference.parse(doc.getId()))).thenReturn(doc);

        stage.process(List.of(doc.getId()), ctx);

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process(doc);
    }

    @Test
    public void test_process_chunked_doc_when_doc_is_large() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("huge_doc").with("0123456789abcdef0123456789abcdef+").build();
        when(pipeline.process(doc)).thenReturn(emptyList());
        when(indexer.get("projectName", DocReference.parse(doc.getId()))).thenReturn(doc);

        stage.process(List.of(doc.getId()), ctx);

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process(doc, 32, 0);
        verify(pipeline).process(doc, 32, 32);
    }

    @Test
    public void test_with_routing_fetches_doc_with_root_id() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document doc = createDoc("embedded").withRootId("rootId").withParentId("rootId").build();
        when(pipeline.process(doc)).thenReturn(emptyList());
        when(indexer.get("projectName", DocReference.parse(doc.getId() + "|rootId"))).thenReturn(doc);

        stage.process(List.of(doc.getId() + "|rootId"), ctx);

        verify(pipeline).initialize(ENGLISH);
        verify(pipeline).process(doc);
    }

    @Test
    public void test_processes_every_document_of_the_batch() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document first = createDoc("first").build();
        Document second = createDoc("second").build();
        when(pipeline.process(any(Document.class))).thenReturn(emptyList());
        when(indexer.get("projectName", DocReference.parse(first.getId()))).thenReturn(first);
        when(indexer.get("projectName", DocReference.parse(second.getId()))).thenReturn(second);

        StageResult result = stage.process(List.of(first.getId(), second.getId()), ctx);

        verify(pipeline).process(first);
        verify(pipeline).process(second);
        assertThat(result.updated()).isEqualTo(2);
    }

    @Test
    public void test_an_interruption_returns_at_once_with_what_was_done() throws Exception {
        when(pipeline.initialize(any())).thenReturn(true);
        Document first = createDoc("first").build();
        Document second = createDoc("second").build();
        Document third = createDoc("third").build();
        when(pipeline.process(first)).thenReturn(emptyList());
        when(pipeline.process(second)).thenThrow(new InterruptedException());
        when(indexer.get("projectName", DocReference.parse(first.getId()))).thenReturn(first);
        when(indexer.get("projectName", DocReference.parse(second.getId()))).thenReturn(second);
        when(indexer.get("projectName", DocReference.parse(third.getId()))).thenReturn(third);

        StageResult result = stage.process(List.of(first.getId(), second.getId(), third.getId()), ctx);

        assertThat(result).isEqualTo(new StageResult(1, 0, 1, 1));
        verify(pipeline, never()).process(third);
    }
}
