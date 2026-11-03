package org.icij.datashare.tasks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import static org.fest.assertions.Assertions.assertThat;
import org.icij.datashare.asynctasks.StageContext;
import org.icij.datashare.asynctasks.StageResult;
import org.icij.datashare.text.DocReference;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.DocumentBuilder;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestEntry;
import org.icij.datashare.text.artifact.ManifestEntryStatus;
import org.icij.datashare.text.indexing.Indexer;
import org.jspecify.annotations.NonNull;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ArtifactStageTest {
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();
    private static final String EMBEDDED_DOC_SHA256 = "0f95ef97e4619f7bae2a585c6cf24587cd7a3a81a26599c8774d669e5c175e5e";
    private static final String EMBEDDED_PDF_SHA256 = "6abb96950946b62bb993307c8945c0c096982783bab7fa24901522426840ca3e";
    private final Indexer indexer = mock(Indexer.class);
    private final MockIndexer mockIndexer = new MockIndexer(indexer);

    @Test(expected = IllegalArgumentException.class)
    public void test_artifact_stage_no_artifact_directory() {
        new ArtifactStage(indexer).process(List.of(), createCtx(Map.of()));
    }

    @Test
    public void test_artifact_stage_no_doc_ref() {
        assertThat(new ArtifactStage(indexer)
                           .process(List.of(), createCtx(Map.of("artifactDir", tempFolder.getRoot().toString())))
        ).isEqualTo(StageResult.EMPTY);
    }

    @Test
    public void test_configuration_comes_from_the_options() throws Exception {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);

        StageResult result = run(List.of(EMBEDDED_DOC_SHA256), Map.of());

        assertThat(result).isEqualTo(new StageResult(1, 0, 0, 0));
        assertThat(artifactDir("6a/bb").toFile()).isDirectory();
    }

    @Test
    public void test_create_artifact_cache_one_file() throws Exception {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);

        assertThat(run(List.of(EMBEDDED_DOC_SHA256), Map.of()).updated()).isEqualTo(1);

        assertThat(artifactDir("6a/bb/" + EMBEDDED_PDF_SHA256 + "/raw").toFile()).isFile();
        assertThat(artifactDir("6a/bb/" + EMBEDDED_PDF_SHA256 + "/raw.json").toFile()).isFile();
    }

    @Test
    public void test_skip_document_not_found_in_index() throws Exception {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);

        StageResult result = run(List.of("unknownId", EMBEDDED_DOC_SHA256), Map.of());

        assertThat(result).isEqualTo(new StageResult(1, 0, 1, 0));
    }

    @Test
    public void test_entry_with_routing_fetches_document_with_root_id() throws Exception {
        indexEmbeddedDoc("rootId");

        StageResult result = run(List.of(EMBEDDED_DOC_SHA256 + "|rootId"), Map.of("artifacts", "raw"));

        assertThat(result).isEqualTo(new StageResult(0, 0, 0, 1));
    }

    @Test
    public void test_embedded_document_is_parsed_from_its_root_and_gets_its_own_manifest_entry() throws Exception {
        indexEmbeddedPdfUnderItsRoot();

        StageResult result = run(List.of(EMBEDDED_PDF_SHA256 + "|" + EMBEDDED_DOC_SHA256), Map.of("artifacts", "true"));

        assertThat(result.updated()).isEqualTo(1);
        Path docArtifactDir = artifactDir("6a/bb/" + EMBEDDED_PDF_SHA256);
        assertThat(docArtifactDir.resolve("raw").toFile()).isFile();
        ManifestEntry structure = new FilesystemManifestRepository().get(docArtifactDir, "structure");
        assertThat(structure.status()).isEqualTo(ManifestEntryStatus.COMPLETE);
        assertThat(structure.pages().total()).isEqualTo(2);
        assertThat(docArtifactDir.resolve("structure").resolve("page-2.md").toFile()).isFile();
    }

    @Test
    public void test_the_recorded_entry_carries_the_task_id_of_the_options() throws Exception {
        indexEmbeddedPdfUnderItsRoot();

        run(List.of(EMBEDDED_PDF_SHA256 + "|" + EMBEDDED_DOC_SHA256), Map.of("artifacts", "true", "taskId", "myTaskId"));

        assertThat(new FilesystemManifestRepository().get(artifactDir("6a/bb/" + EMBEDDED_PDF_SHA256), "structure").taskId())
                .isEqualTo("myTaskId");
    }

    @Test
    public void test_a_document_failure_wrapping_an_interrupt_is_counted_and_the_rest_is_processed() throws Exception {
        indexEmbeddedDoc(EMBEDDED_DOC_SHA256);
        String failingId = "3333333333333333333333333333333333333333333333333333333333333333";
        when(indexer.get(any(), eq(DocReference.parse(failingId)), any()))
                .thenThrow(new RuntimeException(new InterruptedException("wrapped by the es client")));

        StageResult result = run(List.of(failingId, EMBEDDED_DOC_SHA256), Map.of());

        assertThat(result).isEqualTo(new StageResult(1, 0, 0, 1));
    }

    @Test
    public void test_root_caches_embedded_raw_and_writes_empty_root_manifest() throws Exception {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        mockIndexer.indexFile("prj", EMBEDDED_DOC_SHA256, path, "message/rfc822");

        assertThat(run(List.of(EMBEDDED_DOC_SHA256), Map.of()).updated()).isEqualTo(1);

        // raw bytes for the embedded child are still produced
        assertThat(artifactDir("6a/bb/" + EMBEDDED_PDF_SHA256 + "/raw").toFile()).isFile();
        // a root document records an EMPTY raw entry (source is the on-disk original) so it is not
        // reprocessed on the next run
        Path rootManifest = artifactDir("0f/95/" + EMBEDDED_DOC_SHA256 + "/manifest.json");
        assertThat(rootManifest.toFile()).isFile();
        assertThat(Files.readString(rootManifest)).contains("\"status\" : \"empty\"");
    }

    private StageResult run(List<String> docRefs, Map<String, Object> extraOptions) {
        Map<String, Object> options = new HashMap<>(Map.of(
                "artifactDir", tempFolder.getRoot().toString(), "defaultProject", "prj"));
        options.putAll(extraOptions);
        return new ArtifactStage(indexer).process(docRefs, createCtx(options));
    }

    private Path artifactDir(String relativePath) {
        return tempFolder.getRoot().toPath().resolve("prj").resolve(relativePath);
    }

    private void indexEmbeddedDoc(String rootId) throws Exception {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        mockIndexer.indexFile("prj", EMBEDDED_DOC_SHA256, path, "message/rfc822", rootId);
    }

    private void indexEmbeddedPdfUnderItsRoot() throws Exception {
        Path path = Path.of(Objects.requireNonNull(getClass().getResource("/docs/embedded_doc.eml")).toURI());
        Document root = DocumentBuilder.createDoc(EMBEDDED_DOC_SHA256).with(path)
                .with(Project.project("prj")).ofContentType("message/rfc822").build();
        Document embedded = DocumentBuilder.createDoc(EMBEDDED_PDF_SHA256).with(path)
                .with(Project.project("prj")).ofContentType("application/pdf")
                .withParentId(EMBEDDED_DOC_SHA256).withRootId(EMBEDDED_DOC_SHA256)
                .withExtractionLevel((short) 1).withContentLength(10).build();
        mockIndexer.indexFile("prj", root, embedded);
    }

    private @NonNull StageContext<Void> createCtx(Map<String, Object> options) {
        return new StageContext<>() {
            @Override
            public Project project() {
                return Project.project("prj");
            }

            @Override
            public Map<String, Object> options() {
                HashMap<String, Object> opts = new HashMap<>(options);
                opts.putIfAbsent("taskId", "taskId");
                return opts;
            }

            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void emit(List<Void> out) {}

            @Override
            public <R> R resource(Class<R> type, Supplier<R> factory) {
                return factory.get();
            }

            @Override
            public void close() {}
        };
    }
}