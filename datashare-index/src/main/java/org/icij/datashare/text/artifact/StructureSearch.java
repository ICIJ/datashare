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
     * any real document, which the {@link #MAX_SCANNED_PAGES} cap is not: that cap still allows a
     * manifest to hold one of the ten HTTP worker threads for minutes. Generous enough never to fire
     * on a real corpus, so the answer stays reproducible in practice, and {@code scanned} below
     * {@code pages} tells the client the count is a floor when it does.
     */
    private static final Duration SCAN_BUDGET = Duration.ofSeconds(10);

    /**
     * The most pages one scan walks, however many the manifest advertises. Past this a total is
     * hostile input rather than a long document: manifest.json is written by other producers, and an
     * unbounded loop over {@code Integer.MAX_VALUE} never terminates at all, since the counter wraps
     * to MIN_VALUE and stays in range. It bounds this loop and nothing else, which is why it lives
     * here rather than in {@link ArtifactReader}: a merged archive or a bulk-exported log really can
     * run past a hundred thousand pages, and the manifest and page routes serve it page by page.
     */
    private static final int MAX_SCANNED_PAGES = 100_000;

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
        if (total == null) {
            return null;
        }
        // Page 1 is counted here rather than probed. A formats() probe stats the very file the first
        // loop iteration then reads, which is the stat-then-read round trip ArtifactReader#page was
        // just changed to drop, reintroduced once per request. Null carries the same meaning the
        // empty probe did: without a page 1 in this format the document has "no markdown here"
        // rather than "no occurrences", which the route turns into 404. A gap further in is reported
        // through scanned() instead, as the manifest route reports it.
        Integer first = countPage(entry, 1, query);
        return first == null ? null : scan(entry, total, query, first);
    }

    // Over five lines on purpose: the loop accumulates three values and skips a fourth case, so the
    // only way under the limit is a one-caller helper taking the accumulators as parameters, which
    // hides the algorithm rather than shortening it. Sibling serving code here runs to the same length.
    private Hits scan(ManifestEntry entry, int total, String query, int firstPage) {
        List<PageHits> hits = new ArrayList<>();
        int matches = firstPage;
        if (firstPage > 0) {
            hits.add(new PageHits(1, firstPage));
        }
        // Subtraction rather than a plain comparison, so a nanoTime wrap cannot end the scan early.
        long deadline = System.nanoTime() + scanBudget.toNanos();
        int last = Math.min(total, MAX_SCANNED_PAGES);
        // One already: search() read page 1 to learn whether there is anything here to search.
        int scanned = 1;
        int page = 2;
        for (; page <= last && System.nanoTime() - deadline < 0; page++) {
            Integer count = countPage(entry, page, query);
            if (count == null) {
                continue;
            }
            scanned++;
            if (count > 0) {
                matches += count;
                hits.add(new PageHits(page, count));
            }
        }
        reportIncompleteScan(scanned, page - 1, last, total);
        return new Hits(matches, total, scanned, hits);
    }

    // Three ways to come back short, and the log has to tell them apart: pages the budget never let
    // the scan reach, pages past the cap it will not walk at all, and pages it reached and could not
    // read. Once per scan, not once per page: the reader logs each unreadable page at DEBUG
    // precisely so this stays one line.
    private void reportIncompleteScan(int scanned, int reached, int last, int total) {
        if (reached < last) {
            LOGGER.warn("stopped searching '{}' in {} after {} of {} page(s): the {}s scan budget ran out",
                    TYPE.token(), docArtifactDir, reached, total, scanBudget.toSeconds());
        } else if (last < total) {
            LOGGER.warn("searched the first {} of the {} page(s) the '{}' manifest advertises in {}: one "
                    + "scan walks at most {} page(s)", last, total, TYPE.token(), docArtifactDir,
                    MAX_SCANNED_PAGES);
        } else if (scanned < total) {
            LOGGER.warn("searched {} of the {} page(s) the '{}' manifest advertises in {}: the rest are "
                    + "missing or unreadable", scanned, total, TYPE.token(), docArtifactDir);
        }
    }

    // null when the page could not be read: missing on disk, unreadable (the reader maps every read
    // failure to null), or a read that throws where even that cannot reach, such as a stalled mount.
    // Failing a nine-hundred-page search over one page would be worse, and scanned() carries it out.
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
