package org.icij.datashare.text.artifact;

import org.icij.datashare.text.ContentOccurrences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * In-document search over one document's persisted structure pages, the read-side counterpart of
 * {@link StructureArtifact}. Built per request rather than shared, because the document's dir and
 * the requested format are fixed for a whole scan and holding them keeps every helper inside the
 * four-parameter limit.
 * <p>
 * Two things it does not promise. Reading one page at a time is not a memory bound: a format Tika
 * emits no {@code div.page} for is one page holding the whole document, the same page the single-page
 * route already serves in one response. And a match is counted within a page, so a phrase split by a
 * page break counts zero here and one in the Elasticsearch content search.
 */
public class StructureSearch {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureSearch.class);
    private static final ArtifactType TYPE = ArtifactType.STRUCTURE;

    private final ArtifactReader reader;
    private final Path docArtifactDir;
    private final String extension;

    public StructureSearch(ArtifactReader reader, Path docArtifactDir, String extension) {
        this.reader = reader;
        this.docArtifactDir = docArtifactDir;
        this.extension = extension;
    }

    /** Per-page occurrence counts in page order, pages with none omitted, or null when this
     *  document has nothing servable to search. Callers map null to 404 the way they map the
     *  reader's own nulls, so "not found" keeps one definition across the artifact routes. */
    public Hits search(String query) throws IOException {
        ManifestEntry entry = reader.servableEntry(docArtifactDir, TYPE);
        Integer total = entry == null ? null : reader.servableTotal(docArtifactDir, TYPE, entry);
        // Without the formats probe, a document whose page-N.md files are gone would answer "no
        // occurrences" instead of "no markdown here". The probe checks page 1 only, as the manifest
        // route does; a gap further in is reported through scanned().
        if (total == null || reader.formats(docArtifactDir, TYPE, entry, List.of(extension)).isEmpty()) {
            return null;
        }
        return scan(entry, total, query);
    }

    private Hits scan(ManifestEntry entry, int total, String query) {
        List<PageHits> hits = new ArrayList<>();
        int scanned = 0;
        for (int page = 1; page <= total; page++) {
            Integer count = countPage(entry, page, query);
            if (count == null) {
                continue;
            }
            scanned++;
            if (count > 0) {
                hits.add(new PageHits(page, count));
            }
        }
        if (scanned < total) {
            // Once per scan, not once per page: the reader logs each miss at DEBUG precisely so this
            // stays one line however many pages a degraded artifact has lost.
            LOGGER.warn("searched {} of the {} page(s) the '{}' manifest advertises in {}: the rest are "
                    + "missing or unreadable", scanned, total, TYPE.token(), docArtifactDir);
        }
        return new Hits(hits.stream().mapToInt(PageHits::count).sum(), total, scanned, hits);
    }

    // null when the page could not be read: missing on disk, or a read that throws (the window between
    // isReadable and readAllBytes, a permission change mid-scan, a stalled mount). Failing a
    // nine-hundred-page search over one page would be worse, and scanned() carries the fact out.
    private Integer countPage(ManifestEntry entry, int page, String query) {
        byte[] payload;
        try {
            payload = reader.page(docArtifactDir, TYPE, entry, page, extension);
        } catch (IOException unreadable) {
            LOGGER.debug("page {} of '{}' in {} could not be read", page, TYPE.token(), docArtifactDir, unreadable);
            return null;
        }
        return payload == null ? null : ContentOccurrences.count(new String(payload, UTF_8), query);
    }

    /** The response body. {@code scanned} is below {@code pages} when the artifact lost pages between
     *  the manifest and disk: the counts are then a floor, not a total, and only this field says so. */
    public record Hits(int count, int pages, int scanned, List<PageHits> hits) {}

    public record PageHits(int page, int count) {}
}
