package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RawArtifactTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    @Test
    public void test_type_and_task_input() {
        RawArtifact raw = new RawArtifact();
        assertThat(raw.type()).isEqualTo(ArtifactType.RAW);
        assertThat(raw.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
    }

    @Test
    public void test_produce_runs_embedded_extraction_and_returns_single_file_entry() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/report.pdf")).ofContentType("application/pdf").withExtractionLevel((short) 1).build();
        Path docDir = dir.getRoot().toPath();
        // Mirror extract-lib's side effect: extractEmbeddedSources writes the polled doc's raw bytes.
        doAnswer(invocation -> {
            Files.createFile(docDir.resolve("raw"));
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

    @Test(expected = ArtifactException.class)
    public void test_produce_throws_when_polled_doc_raw_bytes_were_not_written() throws Exception {
        // extractEmbeddedSources runs (e.g. a swallowed per-message parse failure mid-walk) but never
        // writes THIS polled document's raw bytes: produce() must fail loudly, not stamp a terminal entry.
        SourceExtractor sources = mock(SourceExtractor.class); // no-op: writes nothing to disk
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/report.pdf")).ofContentType("application/pdf").withExtractionLevel((short) 1).build();
        ArtifactContext ctx = new ArtifactContext(project, doc, dir.getRoot().toPath(), sources);

        new RawArtifact().produce(ctx);
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

    @Test(expected = ArtifactException.class)
    public void test_produce_wraps_extraction_failure() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/x.pdf")).ofContentType("application/pdf").build();
        org.mockito.Mockito.doThrow(new java.io.IOException("nope")).when(sources).extractEmbeddedSources(project, doc);

        new RawArtifact().produce(new ArtifactContext(project, doc, dir.getRoot().toPath(), sources));
    }
}
