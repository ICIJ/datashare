package org.icij.datashare.text.artifact;

import org.icij.datashare.text.ContentOccurrences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
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

    /**
     * Wall clock a single scan may spend before it answers with what it has. Deliberately far above
     * any real document, which the {@link ArtifactReader#MAX_SERVABLE_PAGES} cap is not: that cap
     * still allows a manifest to hold one of the ten HTTP worker threads for minutes. Generous
     * enough never to fire on a real corpus, so the answer stays reproducible in practice, and
     * {@code scanned} below {@code pages} tells the client the count is a floor when it does.
     */
    private static final Duration SCAN_BUDGET = Duration.ofSeconds(10);

    private final ArtifactReader reader;
    private final Path docArtifactDir;
    private final String extension;
    private final Duration scanBudget;

    public StructureSearch(ArtifactReader reader, Path docArtifactDir, String extension) {
        this(reader, docArtifactDir, extension, SCAN_BUDGET);
    }

    // Package-private: the budget is fixed in production, and no test can wait ten seconds to prove
    // the loop consults it at all.
    StructureSearch(ArtifactReader reader, Path docArtifactDir, String extension, Duration scanBudget) {
        this.reader = reader;
        this.docArtifactDir = docArtifactDir;
        this.extension = extension;
        this.scanBudget = scanBudget;
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

    // Over five lines on purpose: the loop accumulates two values and skips a third case, so the only
    // way under the limit is a one-caller helper taking the accumulators as parameters, which hides
    // the algorithm rather than shortening it. Sibling serving code here runs to the same length.
    private Hits scan(ManifestEntry entry, int total, String query) {
        List<PageHits> hits = new ArrayList<>();
        // Subtraction rather than a plain comparison, so a nanoTime wrap cannot end the scan early.
        long deadline = System.nanoTime() + scanBudget.toNanos();
        int scanned = 0;
        int page = 1;
        for (; page <= total && System.nanoTime() - deadline < 0; page++) {
            Integer count = countPage(entry, page, query);
            if (count == null) {
                continue;
            }
            scanned++;
            if (count > 0) {
                hits.add(new PageHits(page, count));
            }
        }
        reportIncompleteScan(scanned, page - 1, total);
        return new Hits(hits.stream().mapToInt(PageHits::count).sum(), total, scanned, hits);
    }

    // Two ways to come back short, and the log has to tell them apart: pages the scan reached and
    // could not read, versus pages it never reached because the budget ran out. Once per scan, not
    // once per page: the reader logs each unreadable page at DEBUG precisely so this stays one line.
    private void reportIncompleteScan(int scanned, int reached, int total) {
        if (reached < total) {
            LOGGER.warn("stopped searching '{}' in {} after {} of {} page(s): the {}s scan budget ran out",
                    TYPE.token(), docArtifactDir, reached, total, scanBudget.toSeconds());
        } else if (scanned < total) {
            LOGGER.warn("searched {} of the {} page(s) the '{}' manifest advertises in {}: the rest are "
                    + "missing or unreadable", scanned, total, TYPE.token(), docArtifactDir);
        }
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
