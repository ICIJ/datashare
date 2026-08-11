package org.icij.datashare.text.artifact;

import org.icij.datashare.text.ContentOccurrences;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * In-document search over one document's persisted structure pages, the read-side counterpart of
 * {@link StructureArtifact}. Built per request rather than shared, because the document's dir and
 * the requested format are fixed for a whole scan and holding them keeps every helper inside the
 * four-parameter limit. Pages are read one at a time, so a document's whole markdown is never
 * resident at once.
 */
public class StructureSearch {
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
        ManifestEntry entry = searchableEntry();
        if (entry == null) {
            return null;
        }
        return scan(entry, query);
    }

    // The entry only when a servable structure artifact carries this format on disk. Without the
    // formats probe, a document whose page-N.md files are gone would answer "no occurrences"
    // instead of "no markdown here", contradicting what the manifest route told the client. The
    // probe itself only checks page 1 (ArtifactReader#formats), matching what the manifest route
    // advertises: a document missing page-1.md reads as "no markdown here", while a gap further in
    // is just a page with no occurrence.
    private ManifestEntry searchableEntry() throws IOException {
        ManifestEntry entry = reader.servableEntry(docArtifactDir, TYPE);
        if (entry == null || reader.servableTotal(docArtifactDir, TYPE, entry) == null) {
            return null;
        }
        return reader.formats(docArtifactDir, TYPE, entry, List.of(extension)).isEmpty() ? null : entry;
    }

    private Hits scan(ManifestEntry entry, String query) throws IOException {
        List<PageHits> hits = new ArrayList<>();
        int total = reader.servableTotal(docArtifactDir, TYPE, entry);
        for (int page = 1; page <= total; page++) {
            addPage(hits, entry, page, query);
        }
        return new Hits(hits.stream().mapToInt(PageHits::count).sum(), hits);
    }

    private void addPage(List<PageHits> hits, ManifestEntry entry, int page, String query) throws IOException {
        int count = countPage(entry, page, query);
        if (count > 0) {
            hits.add(new PageHits(page, count));
        }
    }

    private int countPage(ManifestEntry entry, int page, String query) throws IOException {
        byte[] payload = reader.page(docArtifactDir, TYPE, entry, page, extension);
        // A page the manifest advertises but disk no longer has: the reader has already logged it
        // and the page route 404s that one page, so here it simply holds no occurrence. Failing a
        // nine-hundred-page search over one missing file would be strictly worse.
        if (payload == null) {
            return 0;
        }
        return ContentOccurrences.count(new String(payload, UTF_8), query);
    }

    /** The response body: the document total, then only the pages carrying an occurrence. */
    public record Hits(int count, List<PageHits> hits) {}

    public record PageHits(int page, int count) {}
}
