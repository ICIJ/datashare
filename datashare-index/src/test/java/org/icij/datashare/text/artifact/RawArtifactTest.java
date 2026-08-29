package org.icij.datashare.text.artifact;

import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.TikaException;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipException;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RawArtifactTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    private final RawArtifact raw = new RawArtifact();

    @Test
    public void test_entry_for_root_is_empty() {
        Document root = createDoc("rootrootrootroot").with(Path.of("/tmp/root.pdf"))
                .ofContentType("application/pdf").withExtractionLevel((short) 0).build();

        ManifestEntry entry = raw.entryFor(root);

        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(entry.filename()).isNull();
    }

    @Test
    public void test_entry_for_embedded_is_single_file() {
        Document embedded = createDoc("embeddedembedded").with(Path.of("/tmp/image2.jpg"))
                .ofContentType("image/jpeg").withExtractionLevel((short) 1).build();

        ManifestEntry entry = raw.entryFor(embedded);

        assertThat(entry.status()).isNull();
        assertThat(entry.contentType()).isEqualTo("image/jpeg");
        assertThat(entry.filename()).isEqualTo("image2.jpg");
        assertThat(entry.taskInput()).isEqualTo(raw.taskInput());
    }

    @Test
    public void test_entry_for_a_document_whose_labels_disagree_is_not_empty() {
        // A rootId pointing elsewhere sends SourceExtractor down the embedded path, so recording this one
        // as a root would stamp "processed, source on disk" over a document whose source is not on disk.
        Document disagreeing = createDoc("embeddedembedded").with(Path.of("/tmp/container.eml"))
                .ofContentType("image/jpeg").withExtractionLevel((short) 0).withRootId("rootrootrootroot").build();

        ManifestEntry entry = raw.entryFor(disagreeing);

        assertThat(entry.status()).isNull();
        assertThat(entry.filename()).isEqualTo("container.eml");
    }

    @Test
    public void test_type_and_task_input() {
        assertThat(raw.type()).isEqualTo(ArtifactType.RAW);
        assertThat(raw.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
    }

    @Test
    public void test_produce_runs_embedded_extraction_and_returns_single_file_entry() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/report.pdf")).ofContentType("application/pdf").withExtractionLevel((short) 1).build();
        Path docDir = dir.getRoot().toPath();
        // Mirror extract-lib's side effect: extractEmbeddedSources writes the raw bytes and their sidecar.
        doAnswer(invocation -> {
            Files.createFile(docDir.resolve("raw"));
            Files.createFile(docDir.resolve("raw.json"));
            return null;
        }).when(sources).extractEmbeddedSources(project, doc);
        ArtifactContext ctx = new ArtifactContext(project, doc, docDir, sources);

        ManifestEntry entry = new RawArtifact().produce(ctx);

        verify(sources).extractEmbeddedSources(project, doc);
        assertThat(entry).isNotNull();
        assertThat(entry.contentType()).isEqualTo("application/pdf");
        assertThat(entry.filename()).isEqualTo("report.pdf");
        assertThat(entry.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
        assertThat(entry.isComplete()).isFalse(); // registry stamps status, not produce()
    }

    @Test
    public void test_produce_returns_empty_entry_for_root_document() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/root.pdf")).ofContentType("application/pdf").withExtractionLevel((short) 0).build();
        ArtifactContext ctx = new ArtifactContext(project, doc, dir.getRoot().toPath(), sources);

        ManifestEntry entry = new RawArtifact().produce(ctx);

        verify(sources).extractEmbeddedSources(project, doc);
        assertThat(entry.status()).isEqualTo(ManifestEntryStatus.EMPTY);
        assertThat(entry.isComplete()).isFalse();
    }

    @Test
    public void test_produce_reports_encrypted_content_as_unreadable() throws Exception {
        // An encryption method no parser can undo surfaces as a TikaException, not as a type of its own.
        try {
            produceFailingWith(new EncryptedDocumentException("Unsupported feature encryption used in entry"));
            fail("expected an UnreadableContentException");
        } catch (UnreadableContentException expected) {
            assertThat(expected.documentId).isEqualTo("doc-id");
        }
    }

    @Test
    public void test_produce_reports_a_structurally_corrupt_archive_as_unreadable() throws Exception {
        try {
            produceFailingWith(new TikaException("TIKA-198: Illegal IOException from PackageParser",
                    new ZipException("Unexpected record signature")));
            fail("expected an UnreadableContentException");
        } catch (UnreadableContentException expected) {
            assertThat(expected.documentId).isEqualTo("doc-id");
        }
    }

    @Test
    public void test_produce_keeps_a_read_failure_retryable() throws Exception {
        try {
            produceFailingWith(new IOException("stalled mount"));
            fail("expected an ArtifactException");
        } catch (UnreadableContentException unexpected) {
            fail("a read failure is retryable, not unreadable content");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains("raw extraction failed");
        }
    }

    @Test
    public void test_produce_keeps_missing_raw_bytes_retryable() throws Exception {
        // extractEmbeddedSources returns without writing THIS polled document's bytes (a swallowed
        // per-message parse failure mid-walk): a genuine failure a re-run can get past, so it fails loudly.
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/report.pdf")).ofContentType("application/pdf").withExtractionLevel((short) 1).build();

        try {
            new RawArtifact().produce(new ArtifactContext(project, doc, dir.getRoot().toPath(), sources));
            fail("expected an ArtifactException");
        } catch (UnreadableContentException unexpected) {
            fail("missing raw bytes are retryable, not unreadable content");
        } catch (ArtifactException expected) {
            assertThat(expected.getMessage()).contains("raw extraction produced no bytes");
        }
    }

    @Test
    public void test_produce_records_the_bytes_a_failed_walk_had_already_written() throws Exception {
        // The walk writes as it goes: a failure further along the root's tree leaves this document's own
        // bytes on disk, and an empty entry there would disclaim a payload skip-if-current never revisits.
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/image2.jpg"))
                .ofContentType("image/jpeg").withExtractionLevel((short) 1).build();
        Path docDir = dir.getRoot().toPath();
        doAnswer(invocation -> {
            Files.createFile(docDir.resolve("raw"));
            Files.createFile(docDir.resolve("raw.json"));
            throw new TikaException("TIKA-198: Illegal IOException from PackageParser",
                    new ZipException("Unexpected record signature"));
        }).when(sources).extractEmbeddedSources(project, doc);

        ManifestEntry entry = new RawArtifact().produce(new ArtifactContext(project, doc, docDir, sources));

        assertThat(entry.status()).isNull();
        assertThat(entry.contentType()).isEqualTo("image/jpeg");
        assertThat(entry.filename()).isEqualTo("image2.jpg");
    }

    private void produceFailingWith(Exception failure) throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/archive.zip"))
                .ofContentType("application/zip").withExtractionLevel((short) 0).build();
        doThrow(failure).when(sources).extractEmbeddedSources(project, doc);

        new RawArtifact().produce(new ArtifactContext(project, doc, dir.getRoot().toPath(), sources));
    }

    @Test(expected = ArtifactException.class)
    public void test_produce_wraps_null_path_npe() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = mock(Document.class);
        when(doc.getExtractionLevel()).thenReturn((short) 1);
        when(doc.getId()).thenReturn("id");
        when(doc.getName()).thenThrow(new NullPointerException());
        ArtifactContext ctx = new ArtifactContext(project, doc, dir.getRoot().toPath(), sources);

        new RawArtifact().produce(ctx);
    }
}
