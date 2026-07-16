package org.icij.datashare.text.artifact;

import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.fest.assertions.Assertions.assertThat;
import static org.icij.datashare.text.DocumentBuilder.createDoc;
import static org.icij.datashare.text.Project.project;

public class ArtifactCoverageCheckerTest {
    private static final String DOC_ID = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    @Rule public TemporaryFolder artifactDir = new TemporaryFolder();
    private final Project prj = project("prj");
    private final PropertiesProvider props = new PropertiesProvider(Map.of("defaultProject", "prj"));

    private SourceExtractor extractorReturning(byte[] bytes) {
        return new SourceExtractor(props) {
            @Override public InputStream getSource(Project project, Document document) {
                return new ByteArrayInputStream(bytes);
            }
        };
    }

    private void seedTerminalManifest(String docId) throws Exception {
        Path docDir = ArtifactPath.dir(artifactDir.getRoot().toPath().resolve("prj"), docId);
        new FilesystemManifestRepository().put(docDir, "raw",
                ManifestEntry.singleFile(Map.of(), "text/plain", "raw").withStatus(ManifestEntryStatus.COMPLETE));
    }

    @Test
    public void reports_complete_when_manifest_terminal_and_source_streams() throws Exception {
        seedTerminalManifest(DOC_ID);
        Document doc = createDoc(DOC_ID).with(prj).build();

        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(null, extractorReturning("payload".getBytes()))
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(report.complete()).isTrue();
        assertThat(report.checked()).isEqualTo(1);
    }

    @Test
    public void reports_hole_when_manifest_missing() {
        Document doc = createDoc(DOC_ID).with(prj).build();

        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(null, extractorReturning("payload".getBytes()))
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(report.complete()).isFalse();
        assertThat(report.holes().get(0).reason()).contains("manifest");
    }

    @Test
    public void reports_hole_when_getSource_throws() throws Exception {
        seedTerminalManifest(DOC_ID);
        Document doc = createDoc(DOC_ID).with(prj).build();
        SourceExtractor throwing = new SourceExtractor(props) {
            @Override public InputStream getSource(Project project, Document document) {
                throw new RuntimeException("digest never matched");
            }
        };

        ArtifactCoverageChecker.Report failed = new ArtifactCoverageChecker(null, throwing)
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(failed.holes().get(0).reason()).contains("digest never matched");
    }

    @Test
    public void reports_empty_but_not_hole_when_terminal_manifest_and_zero_byte_source() throws Exception {
        seedTerminalManifest(DOC_ID);
        Document doc = createDoc(DOC_ID).with(prj).build();

        ArtifactCoverageChecker.Report report = new ArtifactCoverageChecker(null, extractorReturning(new byte[0]))
                .check(prj, artifactDir.getRoot().toPath(), Stream.of(doc));

        assertThat(report.holes()).isEmpty();
        assertThat(report.complete()).isTrue();
        assertThat(report.empties()).isEqualTo(1);
        assertThat(report.summary()).contains("1 empty embed");
    }
}
