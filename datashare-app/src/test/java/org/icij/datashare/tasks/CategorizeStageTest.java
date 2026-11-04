package org.icij.datashare.tasks;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.icij.datashare.asynctasks.ScopedStageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.ContentTypeCategory;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.junit.Test;
import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

public class CategorizeStageTest {
    private static final String PROJECT = "projectName";
    private final Indexer indexer = mock(Indexer.class);
    private final CategorizeStage stage = new CategorizeStage(indexer);
    private final List<String> emitted = new ArrayList<>();
    private boolean cancelled;
    private final ScopedStageContext<String> ctx = new ScopedStageContext<>() {
        public Project project() {return Project.project(PROJECT);}

        public Map<String, Object> options() {return Map.of("defaultProject", PROJECT);}

        public boolean isCancelled() {return cancelled;}

        public void emit(List<String> out) {emitted.addAll(out);}
    };

    @Test
    public void test_updates_the_doc_with_the_category_of_its_content_type() throws Exception {
        Document video = createDoc("video").ofContentType("video/mp4").build();
        when(indexer.get(PROJECT, DocReference.fromDocument(video))).thenReturn(video);

        StageResult result = stage.process(List.of(video.getId()), ctx);

        ArgumentCaptor<Document> updated = forClass(Document.class);
        verify(indexer).update(eq(PROJECT), updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(video.getId());
        assertThat(updated.getValue().getContentTypeCategory()).isEqualTo(ContentTypeCategory.VIDEO);
        assertThat(result).isEqualTo(new StageResult(1, 0, 0, 0));
    }

    @Test
    public void test_unknown_content_type_is_categorized_as_other() throws Exception {
        Document doc = createDoc("doc").ofContentType("not-a-known/type").build();
        when(indexer.get(PROJECT, DocReference.fromDocument(doc))).thenReturn(doc);

        stage.process(List.of(doc.getId()), ctx);

        ArgumentCaptor<Document> updated = forClass(Document.class);
        verify(indexer).update(eq(PROJECT), updated.capture());
        assertThat(updated.getValue().getContentTypeCategory()).isEqualTo(ContentTypeCategory.OTHER);
    }

    @Test
    public void test_doc_not_found_in_index_is_left_unchanged_and_still_forwarded() throws Exception {
        StageResult result = stage.process(List.of("unknownId"), ctx);

        verify(indexer, never()).update(any(), any(Document.class));
        assertThat(result).isEqualTo(new StageResult(0, 1, 0, 0));
        assertThat(emitted).containsExactly("unknownId");
    }

    @Test
    public void test_id_is_resolved_using_doc_reference() throws Exception {
        Document embedded = createDoc("embedded").ofContentType("application/pdf").withRootId("rootId")
                                                 .withParentId("rootId").build();
        DocReference embeddedRef = DocReference.fromDocument(embedded);
        when(indexer.get(PROJECT, embeddedRef)).thenReturn(embedded);

        StageResult result = stage.process(List.of(embeddedRef.toQueueEntry()), ctx);

        verify(indexer).update(eq(PROJECT), any(Document.class));
        assertThat(result.updated()).isEqualTo(1);
        assertThat(emitted).containsExactly(embeddedRef.toQueueEntry());
    }

    @Test
    public void test_forwards_every_processed_entry_of_the_batch_in_order() {
        Document first = createDoc("first").ofContentType("application/pdf").build();
        Document second = createDoc("second").ofContentType("video/mp4").build();
        when(indexer.get(PROJECT, DocReference.parse(first.getId()))).thenReturn(first);
        when(indexer.get(PROJECT, DocReference.parse(second.getId()))).thenReturn(second);

        StageResult result = stage.process(List.of(first.getId(), second.getId()), ctx);

        assertThat(result).isEqualTo(new StageResult(2, 0, 0, 0));
        assertThat(emitted).containsExactly(first.getId(), second.getId());
    }

    @Test
    public void test_a_failing_doc_is_counted_as_failed_not_forwarded_and_does_not_stop_the_batch() throws Exception {
        Document first = createDoc("first").ofContentType("application/pdf").build();
        Document failing = createDoc("failing").ofContentType("application/pdf").build();
        Document third = createDoc("third").ofContentType("application/pdf").build();
        when(indexer.get(PROJECT, DocReference.fromDocument(first))).thenReturn(first);
        when(indexer.get(PROJECT, DocReference.fromDocument(failing))).thenReturn(failing);
        when(indexer.get(PROJECT, DocReference.fromDocument(third))).thenReturn(third);
        doThrow(new IOException("cannot write")).when(indexer).update(eq(PROJECT), idEquals(failing));

        StageResult result = stage.process(List.of(first.getId(), failing.getId(), third.getId()), ctx);

        assertThat(result).isEqualTo(new StageResult(2, 0, 0, 1));
        assertThat(emitted).containsExactly(first.getId(), third.getId());
    }

    @Test
    public void test_cancellation_stops_at_once_and_skips_the_remaining_docs() throws Exception {
        Document first = createDoc("first").ofContentType("application/pdf").build();
        Document second = createDoc("second").ofContentType("application/pdf").build();
        Document third = createDoc("third").ofContentType("application/pdf").build();
        when(indexer.get(PROJECT, DocReference.fromDocument(first))).thenAnswer(invocation -> {
            cancelled = true;
            return first;
        });

        StageResult result = stage.process(List.of(first.getId(), second.getId(), third.getId()), ctx);

        assertThat(result).isEqualTo(new StageResult(1, 0, 2, 0));
        assertThat(emitted).containsExactly(first.getId());
        verify(indexer, never()).get(PROJECT, DocReference.parse(second.getId()));
    }

    @Test
    public void test_already_cancelled_processes_nothing() {
        cancelled = true;

        StageResult result = stage.process(List.of("id1", "id2"), ctx);

        assertThat(result).isEqualTo(new StageResult(0, 0, 2, 0));
        assertThat(emitted).isEmpty();
    }

    private static Document idEquals(Document expected) {
        return org.mockito.ArgumentMatchers.argThat(doc -> doc != null && expected.getId().equals(doc.getId()));
    }
}
