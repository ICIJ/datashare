package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class RawArtifactTest {
    @Test
    public void test_type_and_task_input() {
        RawArtifact raw = new RawArtifact();
        assertThat(raw.type()).isEqualTo("raw");
        assertThat(raw.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
    }

    @Test
    public void test_produce_runs_embedded_extraction_and_returns_single_file_entry() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/report.pdf")).ofContentType("application/pdf").build();
        ArtifactContext ctx = new ArtifactContext(project, doc, Path.of("/tmp/node"), sources);

        ManifestEntry entry = new RawArtifact().produce(ctx);

        verify(sources).extractEmbeddedSources(project, doc);
        assertThat(entry.contentType()).isEqualTo("application/pdf");
        assertThat(entry.filename()).isEqualTo("report.pdf");
        assertThat(entry.taskInput()).isEqualTo(Map.of("type", "raw", "version", 1));
        assertThat(entry.isComplete()).isFalse(); // registry stamps status, not produce()
    }

    @Test(expected = ArtifactException.class)
    public void test_produce_wraps_extraction_failure() throws Exception {
        SourceExtractor sources = mock(SourceExtractor.class);
        Project project = Project.project("prj");
        Document doc = createDoc("doc-id").with(Path.of("/path/to/x.pdf")).ofContentType("application/pdf").build();
        org.mockito.Mockito.doThrow(new java.io.IOException("nope")).when(sources).extractEmbeddedSources(project, doc);

        new RawArtifact().produce(new ArtifactContext(project, doc, Path.of("/tmp/node"), sources));
    }
}
