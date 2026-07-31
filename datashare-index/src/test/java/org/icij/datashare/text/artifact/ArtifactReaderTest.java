package org.icij.datashare.text.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

public class ArtifactReaderTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestRepository manifests = new FilesystemManifestRepository();
    private final ArtifactReader reader = new ArtifactReader(manifests);

    private Path withFilesystemPages(int total, String... pages) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(ArtifactPath.payloadPage(node, ArtifactType.PAGE, page, "txt"), pages[page - 1]);
        }
        manifests.put(node, ArtifactType.PAGE.token(),
                ManifestEntry.paginated(Map.of(), Pagination.filesystem(total)).withStatus(ManifestEntryStatus.COMPLETE));
        return node;
    }

    @Test
    public void test_servable_entry_is_null_when_no_manifest() throws Exception {
        assertThat(reader.servableEntry(dir.getRoot().toPath(), ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_for_another_type() throws Exception {
        Path node = withFilesystemPages(1, "page one");
        assertThat(reader.servableEntry(node, ArtifactType.STRUCTURE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_empty_status() throws Exception {
        Path node = dir.getRoot().toPath();
        manifests.put(node, ArtifactType.PAGE.token(), ManifestEntry.empty(Map.of()));
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_status_absent() throws Exception {
        Path node = dir.getRoot().toPath();
        manifests.put(node, ArtifactType.PAGE.token(), ManifestEntry.paginated(Map.of(), Pagination.filesystem(2)));
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_carries_the_total() throws Exception {
        Path node = withFilesystemPages(2, "one", "two");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE).total()).isEqualTo(2);
    }

    @Test
    public void test_page_reads_the_filesystem_page() throws Exception {
        Path node = withFilesystemPages(2, "page one", "page two");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(new String(reader.page(node, ArtifactType.PAGE, entry, 2, "txt"), StandardCharsets.UTF_8)).isEqualTo("page two");
    }

    @Test
    public void test_page_is_null_out_of_range() throws Exception {
        Path node = withFilesystemPages(2, "one", "two");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 0, "txt")).isNull();
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 3, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_the_file_is_missing() throws Exception {
        // total says 3, only 2 files exist: the manifest is authoritative for the count, so page 3
        // must 404 rather than the count shrinking on every request.
        Path node = withFilesystemPages(3, "one", "two");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(entry.total()).isEqualTo(3);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 3, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_extension_does_not_exist() throws Exception {
        Path node = withFilesystemPages(1, "one");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "md")).isNull();
    }
}
