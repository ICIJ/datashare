package org.icij.datashare.text.artifact;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Post-run completeness audit: every document in the index must have a terminal raw
 *  manifest entry and a servable source. Reusable standalone against real projects. */
public class ArtifactCoverageChecker {
    public record Hole(String docId, String rootId, String reason) {}
    public record Report(long checked, List<Hole> holes) {
        public boolean complete() { return holes.isEmpty(); }
        public String summary() {
            StringBuilder sb = new StringBuilder(String.format("checked %d document(s), %d hole(s)%n", checked, holes.size()));
            holes.stream().limit(50).forEach(h -> sb.append(String.format("  %s (root %s): %s%n", h.docId(), h.rootId(), h.reason())));
            if (holes.size() > 50) sb.append(String.format("  ... and %d more%n", holes.size() - 50));
            return sb.toString();
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
        long[] checked = {0};
        documents.forEach(doc -> {
            checked[0]++;
            try {
                ManifestEntry raw = manifests.get(ArtifactPath.dir(projectRoot, doc.getId()), "raw");
                if (raw == null || !raw.isTerminal()) {
                    holes.add(new Hole(doc.getId(), doc.getRootDocument(), "no terminal raw manifest entry"));
                    return; // manifest hole already recorded; checking the source too would only mask it
                }
                try (InputStream source = extractor.getSource(project, doc)) {
                    if (source.read() == -1) {
                        holes.add(new Hole(doc.getId(), doc.getRootDocument(), "source stream is empty"));
                    }
                }
            } catch (Exception e) {
                holes.add(new Hole(doc.getId(), doc.getRootDocument(), e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        });
        return new Report(checked[0], List.copyOf(holes));
    }

    /** Scroll the whole index (mirrors EnqueueFromIndexTask's scroll loop) and check it. */
    public Report check(Project project, Path artifactDir, int scrollSize) throws Exception {
        Indexer.Searcher searcher = indexer.search(List.of(project.name), Document.class)
                .withoutSource("content", "content_translated").limit(scrollSize);
        long checked = 0;
        List<Hole> holes = new ArrayList<>();
        List<? extends org.icij.datashare.Entity> batch = searcher.scroll("60s").toList();
        while (!batch.isEmpty()) {
            Stream<Document> docs = batch.stream().map(e -> (Document) e);
            Report partial = check(project, artifactDir, docs);
            checked += partial.checked();
            holes.addAll(partial.holes());
            batch = searcher.scroll("60s").toList();
        }
        searcher.clearScroll();
        return new Report(checked, List.copyOf(holes));
    }
}
