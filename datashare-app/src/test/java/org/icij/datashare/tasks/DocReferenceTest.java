package org.icij.datashare.tasks;

import org.junit.Test;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;

public class DocReferenceTest {
    @Test
    public void test_parse_bare_id_has_no_root() {
        DocReference ref = DocReference.parse("docId");
        assertThat(ref.id()).isEqualTo("docId");
        assertThat(ref.rootId()).isNull();
    }

    @Test
    public void test_parse_entry_with_root() {
        DocReference ref = DocReference.parse("docId|rootId");
        assertThat(ref.id()).isEqualTo("docId");
        assertThat(ref.rootId()).isEqualTo("rootId");
    }

    @Test
    public void test_parse_splits_on_first_separator_only() {
        DocReference ref = DocReference.parse("docId|rootId|garbage");
        assertThat(ref.id()).isEqualTo("docId");
        assertThat(ref.rootId()).isEqualTo("rootId|garbage");
    }

    @Test
    public void test_to_queue_entry_without_root_is_bare_id() {
        assertThat(new DocReference("docId", null).toQueueEntry()).isEqualTo("docId");
    }

    @Test
    public void test_to_queue_entry_with_root_equal_to_id_is_bare_id() {
        assertThat(new DocReference("docId", "docId").toQueueEntry()).isEqualTo("docId");
    }

    @Test
    public void test_to_queue_entry_with_root() {
        assertThat(new DocReference("docId", "rootId").toQueueEntry()).isEqualTo("docId|rootId");
    }

    @Test
    public void test_round_trip() {
        assertThat(DocReference.parse(new DocReference("docId", "rootId").toQueueEntry()))
                .isEqualTo(new DocReference("docId", "rootId"));
        assertThat(DocReference.parse(new DocReference("docId", null).toQueueEntry()))
                .isEqualTo(new DocReference("docId", null));
    }

    @Test
    public void test_from_root_document_has_no_root() {
        DocReference ref = DocReference.fromDocument(createDoc("docId").build());
        assertThat(ref).isEqualTo(new DocReference("docId", null));
    }

    @Test
    public void test_from_embedded_document_has_root() {
        DocReference ref = DocReference.fromDocument(
                createDoc("docId").withRootId("rootId").withParentId("rootId").build());
        assertThat(ref).isEqualTo(new DocReference("docId", "rootId"));
    }
}
