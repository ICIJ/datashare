package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/** Post-run completeness audit: every document in the index must have a terminal raw
 *  manifest entry and a servable source. Reusable standalone against real projects. */
public class ArtifactCoverageChecker {
    // Keep the audit's scroll alive just long enough to fetch the next batch; the whole walk
    // is many short scrolls, not one long-lived context.
    private static final String SCROLL_DURATION = "60s";
    // summary() prints at most this many holes inline and tails the rest as a count, so a run
    // with thousands of holes still logs a bounded, readable line.
    private static final int MAX_HOLES_IN_SUMMARY = 50;

    public record Hole(String docId, String rootId, String reason) {}
    /** empties: docs with a terminal manifest and a readable but zero-byte source. Legitimately
     *  empty (e.g. empty mail-item nodes), so they are retrievable and NOT counted as holes. */
    public record Report(long checked, List<Hole> holes, long empties) {
        public boolean complete() { return holes.isEmpty(); }
        public String summary() {
            StringBuilder builder = new StringBuilder(String.format("checked %d document(s), %d hole(s)%n", checked, holes.size()));
            holes.stream().limit(MAX_HOLES_IN_SUMMARY).forEach(hole ->
                    builder.append(String.format("  %s (root %s): %s%n", hole.docId(), hole.rootId(), hole.reason())));
            if (holes.size() > MAX_HOLES_IN_SUMMARY) {
                builder.append(String.format("  ... and %d more%n", holes.size() - MAX_HOLES_IN_SUMMARY));
            }
            if (empties > 0) {
                builder.append(String.format("%d empty embed(s) (present, zero-byte, not counted as holes)%n", empties));
            }
            return builder.toString();
        }
    }

    private final FilesystemManifestRepository manifests = new FilesystemManifestRepository();
    private final Indexer indexer;
    private final SourceExtractor extractor;

    public ArtifactCoverageChecker(Indexer indexer, SourceExtractor extractor) {
        this.indexer = indexer;
        this.extractor = extractor;
    }

    public Report check(Project project, Path artifactDir, Stream<Document> documents) {
        Path projectRoot = artifactDir.resolve(project.name);
        List<Hole> holes = new ArrayList<>();
        long checked = 0;
        long empties = 0;
        // Iterate explicitly instead of documents.forEach(...): a plain loop keeps the running
        // counters as ordinary locals (no array-box captured and mutated from a lambda) and reads
        // straight down. Callers pass a sequential stream (one scroll batch, or a single test doc).
        Iterator<Document> iterator = documents.iterator();
        while (iterator.hasNext()) {
            Document document = iterator.next();
            checked++;
            try {
                ManifestEntry raw = manifests.get(ArtifactPath.dir(projectRoot, document.getId()), ArtifactType.RAW.token());
                // A missing or non-terminal manifest is the real gap: record it and stop here.
                // Reading the source too would only mask the same failure behind a second symptom.
                if (raw == null || !raw.isTerminal()) {
                    holes.add(new Hole(document.getId(), document.getRootDocument(), "no terminal raw manifest entry"));
                    continue;
                }
                // A terminal manifest entry plus a readable source (even 0-byte) counts as covered:
                // some embeds are legitimately empty (empty mail-item nodes, empty message bodies),
                // so count those separately rather than flag them as holes (false positives).
                try (InputStream source = extractor.getSource(project, document)) {
                    if (source.read() == -1) {
                        empties++;
                    }
                }
            } catch (Exception failure) {
                holes.add(new Hole(document.getId(), document.getRootDocument(),
                        failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        return new Report(checked, List.copyOf(holes), empties);
    }

    /** Scroll the whole index (mirrors EnqueueFromIndexTask's scroll loop) and check it. */
    public Report check(Project project, Path artifactDir, int scrollSize) throws Exception {
        Indexer.Searcher searcher = indexer.search(List.of(project.name), Document.class)
                .withoutSource("content", "content_translated").limit(scrollSize);
        long checked = 0;
        long empties = 0;
        List<Hole> holes = new ArrayList<>();
        try {
            List<? extends org.icij.datashare.Entity> batch = searcher.scroll(SCROLL_DURATION).toList();
            while (!batch.isEmpty()) {
                Stream<Document> documents = batch.stream().map(entity -> (Document) entity);
                Report partial = check(project, artifactDir, documents);
                checked += partial.checked();
                holes.addAll(partial.holes());
                empties += partial.empties();
                batch = searcher.scroll(SCROLL_DURATION).toList();
            }
        } finally {
            // Always release the ES scroll context, even if a scroll batch throws mid-audit,
            // so repeated failed audit runs don't leak open scrolls on the cluster.
            searcher.clearScroll();
        }
        return new Report(checked, List.copyOf(holes), empties);
    }
}
