package org.icij.datashare.text.artifact;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.fest.assertions.Assertions.assertThat;

/** Covers one invariant: a search answers only for a document whose structure artifact is servable
 *  in the requested format, and then counts every page that manifest advertises. */
public class StructureSearchTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();
    private final ManifestRepository manifests = new FilesystemManifestRepository();
    private final ArtifactReader reader = new ArtifactReader(manifests);

    private StructureSearch markdownSearch() {
        return new StructureSearch(reader, dir.getRoot().toPath(), "md");
    }

    // Written through ArtifactPath, never through an inline page-N format: a duplicated format
    // string would let writer and reader drift together and hide a naming bug from these tests.
    private Path writePages(String extension, String... pages) throws Exception {
        Path node = dir.getRoot().toPath();
        Files.createDirectories(ArtifactPath.payloadDir(node, ArtifactType.STRUCTURE));
        for (int page = 1; page <= pages.length; page++) {
            Files.writeString(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, page, extension), pages[page - 1]);
        }
        return node;
    }

    // Advertises `total` pages regardless of how many were written, so a test can claim more.
    private void completeManifest(int total) throws Exception {
        manifests.put(dir.getRoot().toPath(), ArtifactType.STRUCTURE.token(),
                ManifestEntry.paginated(Map.of(), total).withStatus(ManifestEntryStatus.COMPLETE));
    }

    @Test
    public void test_search_is_null_without_a_manifest() throws Exception {
        writePages("md", "data");
        assertThat(markdownSearch().search("data")).isNull();
    }

    @Test
    public void test_search_is_null_when_the_entry_is_never_stamped_complete() throws Exception {
        Path node = writePages("md", "data");
        // Paginated and on disk, but no status: the strict store reads a half-written artifact as
        // absent rather than serving what a crashed producer happened to leave behind.
        manifests.put(node, ArtifactType.STRUCTURE.token(), ManifestEntry.paginated(Map.of(), 1));
        assertThat(markdownSearch().search("data")).isNull();
    }

    @Test
    public void test_search_is_null_when_the_pages_block_has_no_usable_total() throws Exception {
        Path node = writePages("md", "data");
        // Pages.total is a primitive, so an absent total deserializes to 0: complete, but malformed.
        Files.writeString(node.resolve(ArtifactPath.MANIFEST_FILE),
                "{\"structure\": {\"status\": \"complete\", \"taskInput\": {}, "
                        + "\"pages\": {\"pagination\": {\"type\": \"filesystem\"}}}}");
        assertThat(markdownSearch().search("data")).isNull();
    }

    @Test
    public void test_search_is_null_when_the_format_is_absent_from_disk() throws Exception {
        writePages("md", "data");
        completeManifest(1);
        // Same document, same manifest, only the requested format differs, so the null below can
        // come from nothing but the missing format.
        assertThat(markdownSearch().search("data")).isNotNull();
        assertThat(new StructureSearch(reader, dir.getRoot().toPath(), "xhtml").search("data")).isNull();
    }

    @Test
    public void test_search_counts_every_page_in_order_and_omits_the_empty_ones() throws Exception {
        writePages("md", "data and data", "nothing here", "data");
        completeManifest(3);
        StructureSearch.Hits hits = markdownSearch().search("data");
        assertThat(hits.count()).isEqualTo(3);
        assertThat(hits.hits()).hasSize(2);
        assertThat(hits.hits().get(0).page()).isEqualTo(1);
        assertThat(hits.hits().get(0).count()).isEqualTo(2);
        assertThat(hits.hits().get(1).page()).isEqualTo(3);
        assertThat(hits.hits().get(1).count()).isEqualTo(1);
    }

    @Test
    public void test_a_document_with_no_match_answers_zero_rather_than_nothing() throws Exception {
        writePages("md", "# one", "# two");
        completeManifest(2);
        // The distinction the route turns into 200 versus 404: "searched, found none" is not
        // "there is nothing here to search".
        StructureSearch.Hits hits = markdownSearch().search("data");
        assertThat(hits.count()).isEqualTo(0);
        assertThat(hits.hits()).isEmpty();
    }

    @Test
    public void test_a_page_missing_on_disk_holds_no_occurrence_and_the_scan_goes_on() throws Exception {
        Path node = writePages("md", "data", "data", "data");
        // A gap in the middle, not a truncated tail: the manifest still promises page 2, and the
        // scan has to carry on to page 3 rather than stopping or failing at the missing file.
        Files.delete(ArtifactPath.payloadPage(node, ArtifactType.STRUCTURE, 2, "md"));
        completeManifest(3);
        StructureSearch.Hits hits = markdownSearch().search("data");
        assertThat(hits.count()).isEqualTo(2);
        assertThat(hits.hits()).hasSize(2);
        assertThat(hits.hits().get(0).page()).isEqualTo(1);
        assertThat(hits.hits().get(1).page()).isEqualTo(3);
    }

    @Test
    public void test_a_page_is_folded_like_the_elasticsearch_content_search() throws Exception {
        writePages("md", "R\u00e9sum\u00e9 of DATA");
        completeManifest(1);
        // Delegation smoke test only: ContentOccurrencesTest owns the full rule set.
        assertThat(markdownSearch().search("resume").count()).isEqualTo(1);
        assertThat(markdownSearch().search("data").count()).isEqualTo(1);
    }
}
