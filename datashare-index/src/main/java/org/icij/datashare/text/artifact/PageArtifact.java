package org.icij.datashare.text.artifact;

import org.apache.tika.Tika;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.task.Options;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.icij.datashare.cli.DatashareCliOptions.OCR_OPT;

/** The page artifact: a document's paginated PLAIN extracted text, written as a single
 *  pages/content.txt whose per-page half-open byte ranges live in the manifest entry. */
public class PageArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.PAGE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";

    private final PropertiesProvider propertiesProvider;

    public PageArtifact(PropertiesProvider propertiesProvider) {
        this.propertiesProvider = propertiesProvider;
    }

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        // The run's OCR setting, not the per-document one: taskInput() gets no document, and its
        // contract keeps per-document state out so the same doc compares equal across batches.
        return Map.of("pipeline", "tika", "version", tikaVersion(), "ocr", ocrEnabled());
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        Document document = context.document();
        try {
            List<String> pages = extractPages(document, sourcePath(context));
            // Nothing to serve and nothing to write: no page divs means no pages, which is what the
            // live endpoint returns for such a document too. EMPTY is terminal, so the document is
            // recorded once and not reprocessed on every run.
            if (pages.isEmpty()) {
                return ManifestEntry.empty(taskInput());
            }
            return ManifestEntry.paginated(taskInput(), writePages(context, pages));
        } catch (Exception failure) {
            throw new ArtifactException("page extraction failed for " + document.getId(), failure);
        }
    }

    // A root's bytes are the original file; an embed's are its own cached raw payload, so an embed
    // costs one parse of itself instead of one parse of the whole root (289 root parses for a
    // 289-embed OST). getSource() writes that payload when it is missing, as a side effect of
    // extracting the embed from its root.
    private Path sourcePath(ArtifactContext context) throws IOException {
        Document document = context.document();
        if (document.getExtractionLevel() <= 0) {
            return document.getPath();
        }
        Path raw = context.docArtifactDir().resolve(ArtifactPath.RAW_FILE);
        if (Files.notExists(raw)) {
            context.sources().getSource(context.project(), document).close();
        }
        if (Files.notExists(raw)) {
            throw new IOException("no raw payload to paginate for embedded document " + document.getId());
        }
        return raw;
    }

    // One Extractor per document, as the live endpoint does per request: disableOcr() is one-way, so
    // a document indexed without OCR cannot share an Extractor with one indexed with it. embedOutput
    // is deliberately left unset: this producer writes its own payload and nothing else.
    private List<String> extractPages(Document document, Path source) throws IOException {
        Hasher hasher = Hasher.valueOf(document.getId().length());
        DocumentFactory documentFactory = new DocumentFactory()
                .configure(Options.from(Map.of("digestAlgorithm", hasher.toStringWithoutDash())));
        try (Extractor extractor = new Extractor(documentFactory, Options.from(propertiesProvider.getProperties()))) {
            // Same rule as DocumentResource.getPages: a document indexed without OCR must be
            // paginated without OCR, or its pages would not match its indexed content.
            if (document.getOcrParser() == null) {
                extractor.disableOcr();
            }
            return extractor.extractPages(source);
        }
    }

    // Offsets are the byte counts actually written, so the recorded ranges cannot disagree with the
    // file: half-open [start, end), contiguous, first start 0, last end == file length.
    private static List<long[]> writePages(ArtifactContext context, List<String> pages) throws IOException {
        Files.createDirectories(ArtifactPath.pagesDir(context.docArtifactDir()));
        List<long[]> ranges = new ArrayList<>();
        long offset = 0;
        try (OutputStream out = Files.newOutputStream(ArtifactPath.pagesContent(context.docArtifactDir()))) {
            for (String page : pages) {
                byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
                out.write(bytes);
                ranges.add(new long[]{offset, offset + bytes.length});
                offset += bytes.length;
            }
        }
        return ranges;
    }

    private boolean ocrEnabled() {
        return propertiesProvider.get(OCR_OPT).map(Boolean::parseBoolean).orElse(true);
    }

    private static String tikaVersion() {
        return Tika.getString().replace(TIKA_PREFIX, "").strip();
    }
}
