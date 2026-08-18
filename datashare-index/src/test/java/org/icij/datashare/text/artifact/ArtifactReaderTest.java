package org.icij.datashare.text.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.fest.assertions.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

public class ArtifactReaderTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestRepository manifests = new FilesystemManifestRepository();
    private final ArtifactReader reader = new ArtifactReader(manifests);

    // Nothing in Java writes the byte-range scheme (see ByteRangePagination), so there is no
    // production factory for it: the read side is exercised from a hand-built entry.
    private static ManifestEntry byteRangeEntry(int total, List<long[]> ranges) {
        Pages pages = new Pages(total, new ByteRangePagination(ByteRangePagination.TYPE, ranges));
        return new ManifestEntry(null, Map.of(), pages, null, null, null, null);
    }

    private Path withFilesystemPages(int total, String... pages) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(ArtifactPath.payloadPage(node, ArtifactType.PAGE, page, "txt"), pages[page - 1]);
        }
        manifests.put(node, ArtifactType.PAGE.token(),
                ManifestEntry.paginated(Map.of(), total).withStatus(ManifestEntryStatus.COMPLETE));
        return node;
    }

    private Path withByteRanges(String content, String extension, long[]... ranges) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, extension), content);
        manifests.put(node, ArtifactType.PAGE.token(),
                byteRangeEntry(ranges.length, List.of(ranges))
                        .withStatus(ManifestEntryStatus.COMPLETE));
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
    public void test_servable_entry_is_null_when_manifest_json_is_malformed() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(node);
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE), "{not valid json");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_manifest_json_is_not_an_object() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(node);
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE), "[]");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_status_is_unknown() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(node);
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE), "{\"page\": {\"status\": \"bogus\", \"taskInput\": {}}}");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_the_pagination_scheme_is_unknown() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(node);
        // Pagination is sealed over the two schemes datashare knows, so a producer advertising a
        // third one fails to deserialize: that reads as "not found", not as a 500 for the caller.
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE), "{\"page\": {\"status\": \"complete\", "
                + "\"taskInput\": {}, \"pages\": {\"total\": 1, \"pagination\": {\"type\": \"sqlite\"}}}}");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_is_null_when_status_absent() throws Exception {
        Path node = dir.getRoot().toPath();
        manifests.put(node, ArtifactType.PAGE.token(), ManifestEntry.paginated(Map.of(), 2));
        assertThat(reader.servableEntry(node, ArtifactType.PAGE)).isNull();
    }

    @Test
    public void test_servable_entry_carries_the_total() throws Exception {
        Path node = withFilesystemPages(2, "one", "two");
        assertThat(reader.servableEntry(node, ArtifactType.PAGE).pages().total()).isEqualTo(2);
    }

    @Test
    public void test_nothing_is_servable_when_the_pages_block_has_no_total() throws Exception {
        Path node = dir.getRoot().toPath();
        // Pages.total is a primitive, so an absent total deserializes to 0, not to null: the
        // entry is complete but the manifest is malformed, which the strict store reads as absent.
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE),
                "{\"page\": {\"status\": \"complete\", \"taskInput\": {}, "
                        + "\"pages\": {\"pagination\": {\"type\": \"filesystem\"}}}}");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(entry).isNotNull();
        assertThat(reader.servableTotal(node, ArtifactType.PAGE, entry)).isNull();
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_a_total_past_a_hundred_thousand_is_still_servable() throws Exception {
        Path node = dir.getRoot().toPath();
        // A merged archive or a bulk-exported log really can run past a hundred thousand pages, and
        // nothing here loops over the total: bounding a scan is the searcher's business, not the
        // reader's, so a big document must not 404 on the manifest and page routes.
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE),
                "{\"page\": {\"status\": \"complete\", \"taskInput\": {}, "
                        + "\"pages\": {\"total\": 150000, \"pagination\": {\"type\": \"filesystem\"}}}}");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.servableTotal(node, ArtifactType.PAGE, entry)).isEqualTo(150000);
    }

    @Test
    public void test_page_is_null_when_a_directory_sits_where_the_page_file_belongs() throws Exception {
        Path node = withFilesystemPages(1, "page one");
        Path file = ArtifactPath.payloadPage(node, ArtifactType.PAGE, 1, "txt");
        Files.delete(file);
        // Neither NoSuchFile nor AccessDenied: a plain FileSystemException, which the isReadable
        // pre-check used to answer false for. It has to stay a 404 rather than escape as a 500.
        Files.createDirectory(file);
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
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
        assertThat(entry.pages().total()).isEqualTo(3);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 3, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_extension_does_not_exist() throws Exception {
        Path node = withFilesystemPages(1, "one");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "md")).isNull();
    }

    @Test
    public void test_page_reads_a_byte_range_slice() throws Exception {
        Path node = withByteRanges("page onepage two", "txt", new long[]{0, 8}, new long[]{8, 16});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(new String(reader.page(node, ArtifactType.PAGE, entry, 1, "txt"), StandardCharsets.UTF_8)).isEqualTo("page one");
        assertThat(new String(reader.page(node, ArtifactType.PAGE, entry, 2, "txt"), StandardCharsets.UTF_8)).isEqualTo("page two");
    }

    @Test
    public void test_page_is_null_when_a_range_runs_past_end_of_file() throws Exception {
        Path node = withByteRanges("short", "txt", new long[]{0, 5}, new long[]{5, 99});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 2, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_the_content_file_is_missing() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{0, 7});
        Files.delete(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    // Payloads are written by whichever process produced them, sometimes 0600 under another uid,
    // so an unreadable payload must read as "not found" instead of throwing an IOException the
    // serving side would turn into a 500.
    private void assumeUnreadable(Path file) throws Exception {
        Files.setPosixFilePermissions(file, Set.of());
        // Skipped rather than asserted when the test uid reads a mode-000 file anyway (root).
        assumeTrue(!Files.isReadable(file));
    }

    @Test
    public void test_page_is_null_when_the_filesystem_page_is_unreadable() throws Exception {
        Path node = withFilesystemPages(1, "page one");
        assumeUnreadable(ArtifactPath.payloadPage(node, ArtifactType.PAGE, 1, "txt"));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_the_byte_ranges_content_is_unreadable() throws Exception {
        Path node = withByteRanges("page one", "txt", new long[]{0, 8});
        assumeUnreadable(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_formats_omits_an_unreadable_payload() throws Exception {
        Path node = withFilesystemPages(1, "one");
        assumeUnreadable(ArtifactPath.payloadPage(node, ArtifactType.PAGE, 1, "txt"));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("txt"))).isEmpty();
    }

    @Test
    public void test_formats_probes_page_one_under_filesystem_pagination() throws Exception {
        Path node = withFilesystemPages(1, "one");
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("txt", "md"))).containsExactly("txt");
    }

    @Test
    public void test_formats_probes_the_content_file_under_byte_ranges() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{0, 7});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("md", "txt"))).containsExactly("txt");
    }

    @Test
    public void test_page_is_null_when_range_has_negative_start() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{-1, 5});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_range_is_inverted() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{9, 4});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_range_exceeds_max_int_size() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{0, (long) Integer.MAX_VALUE + 1});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_page_returns_empty_slice_for_zero_length_range() throws Exception {
        Path node = withByteRanges("content", "txt", new long[]{4, 4});
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        byte[] result = reader.page(node, ArtifactType.PAGE, entry, 1, "txt");
        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    public void test_page_is_null_when_total_exceeds_range_list_size() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"), "01234567");
        // total=3 with only 2 ranges: page 3 passes page > total guard but fails ranges.size() < page
        manifests.put(node, ArtifactType.PAGE.token(),
                byteRangeEntry(3, List.of(new long[]{0, 4}, new long[]{4, 8}))
                        .withStatus(ManifestEntryStatus.COMPLETE));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 3, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_byte_ranges_list_is_null() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"), "content");
        // Entry with type="byteRanges" but null byteRanges list (legitimately deserialized when field absent)
        manifests.put(node, ArtifactType.PAGE.token(),
                byteRangeEntry(1, null)
                        .withStatus(ManifestEntryStatus.COMPLETE));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_page_is_null_when_range_is_malformed_wrong_length() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"), "content");
        // Range with length 1 instead of 2
        manifests.put(node, ArtifactType.PAGE.token(),
                byteRangeEntry(1, List.of(new long[]{0}))
                        .withStatus(ManifestEntryStatus.COMPLETE));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.page(node, ArtifactType.PAGE, entry, 1, "txt")).isNull();
    }

    @Test
    public void test_formats_preserves_candidate_order() throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.PAGE));
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "txt"), "content");
        Files.writeString(ArtifactPath.payloadContent(node, ArtifactType.PAGE, "md"), "content");
        manifests.put(node, ArtifactType.PAGE.token(),
                byteRangeEntry(1, List.of(new long[]{0, 7}))
                        .withStatus(ManifestEntryStatus.COMPLETE));
        ManifestEntry entry = reader.servableEntry(node, ArtifactType.PAGE);
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("md", "txt"))).containsExactly("md", "txt");
        assertThat(reader.formats(node, ArtifactType.PAGE, entry, List.of("txt", "md"))).containsExactly("txt", "md");
    }
}
