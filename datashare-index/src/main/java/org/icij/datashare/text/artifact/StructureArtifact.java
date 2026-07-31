package org.icij.datashare.text.artifact;

import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The structure artifact: a per-page Tika rendering written as page-NNNN.xhtml (sanitized, the
 *  source of truth) and page-NNNN.md (derived from it) under the document's structure/ dir. */
public class StructureArtifact implements Artifact {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureArtifact.class);
    private static final ArtifactType TYPE = ArtifactType.STRUCTURE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";
    // Staging dirs live in the document dir, so every rename below is same-filesystem and therefore
    // atomic, and are named so neither the manifest nor the serving side ever reads them.
    private static final String STAGING_DIR_PREFIX = ".structure-";
    private static final String REPLACED_SUFFIX = ".replaced";

    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        // A Tika upgrade changes the XHTML, so skip-if-current must see already-cached pages as stale.
        // The datashare version covers the rendering this class owns (page grouping, safelist, flexmark
        // options), which no dependency version tracks. Deliberately conservative: any datashare release
        // makes every structure artifact stale, so the next ARTIFACT run re-extracts a whole corpus.
        return Map.of("pipeline", "tika", "version", tikaVersion(), "datashare", DatashareVersion.VALUE);
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        try (InputStream source = context.sources().getSource(context.project(), context.document())) {
            List<Page> pages = parse(source, context.document());
            writePages(context.docArtifactDir(), pages);
            return ManifestEntry.paginated(taskInput(), pages.size());
        } catch (UnparseableContentException unparseable) {
            throw unparseable;
        } catch (Exception failure) {
            throw new ArtifactException("structure extraction failed for " + context.document().getId(), failure);
        }
    }

    // An IOException is left unclassified on purpose: it is the source going wrong (a stalled mount, a
    // truncated read), which a re-run can get past, while a mislabelled file is what it is forever. A
    // cancelled parse also arrives as a TikaException, so this is not the last word: the producer asks
    // whether the run was cancelled before it records anything.
    private List<Page> parse(InputStream source, Document document) throws IOException, UnparseableContentException {
        try {
            return extractor.extract(source, document.getContentType());
        } catch (TikaException | SAXException unparseable) {
            throw new UnparseableContentException(document.getId(), unparseable);
        }
    }

    // The document's artifact dir is created rather than assumed: with raw out of the selection nothing
    // else creates it, so an ARTIFACT run over a fresh artifactDir would fail on every document.
    static void writePages(Path docArtifactDir, List<Page> pages) throws IOException {
        Files.createDirectories(docArtifactDir);
        Path staging = createStagingDir(docArtifactDir);
        Path aside = staging.resolveSibling(staging.getFileName() + REPLACED_SUFFIX);
        Throwable failure = null;
        try {
            for (int index = 0; index < pages.size(); index++) {
                write(staging, index + 1, pages.get(index));
            }
            swapIntoPlace(staging, aside, docArtifactDir);
        } catch (Throwable thrown) {
            failure = thrown;
            throw thrown;
        } finally {
            // Throwable, not Exception: an OutOfMemoryError while writing pages is a documented failure
            // mode for these corpora, and the staging name is unique per invocation, so what it leaves
            // behind is never reclaimed by anything.
            discard(staging, failure);
        }
    }

    // Files.createDirectory, not createTempDirectory: the JDK stamps a temp directory owner-only, and this
    // one is renamed into place as structure/, which every uid sharing the artifactDir has to read. The
    // random name still matters: two workers on the same digest must not share a staging directory.
    private static Path createStagingDir(Path docArtifactDir) throws IOException {
        return Files.createDirectory(docArtifactDir.resolve(STAGING_DIR_PREFIX + UUID.randomUUID()));
    }

    // Two renames rather than a delete then a move: the payload being replaced is only destroyed once the
    // new page set holds its place, so a failure anywhere here leaves the old pages readable under the
    // manifest entry that still describes them. Residual window: a JVM death between the two renames
    // leaves structure/ missing until the document is re-run with --artifactsForce. A concurrent producer
    // of the same digest can make either rename fail, and nothing here can tell whose payload the path
    // holds, so that document fails loudly rather than stamping a page count over someone else's bytes.
    private static void swapIntoPlace(Path newPages, Path aside, Path docArtifactDir) throws IOException {
        Path structureDir = ArtifactPath.structureDir(docArtifactDir);
        if (!Files.exists(structureDir)) {
            Files.move(newPages, structureDir, StandardCopyOption.ATOMIC_MOVE);
            return;
        }
        Files.move(structureDir, aside, StandardCopyOption.ATOMIC_MOVE);
        try {
            Files.move(newPages, structureDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException moveFailure) {
            restore(aside, structureDir, moveFailure);
            throw moveFailure;
        }
        // Here rather than in the caller's finally: a failed restore above deliberately keeps the aside as
        // the last copy of those pages.
        discard(aside, null);
    }

    // A restore that fails too leaves the only copy of the payload in the holding pen while structure/ is
    // missing under a manifest entry that still says complete, and skip-if-current asks the entry alone
    // (ManifestEntry#isCurrentFor), never whether the payload is there: name the path at ERROR so an
    // operator can rename it back by hand, since only --artifactsForce repairs it otherwise.
    private static void restore(Path aside, Path structureDir, IOException moveFailure) {
        try {
            Files.move(aside, structureDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException restoreFailure) {
            moveFailure.addSuppressed(restoreFailure);
            LOGGER.error("cannot restore the structure payload of {}: it is left in {}, rename it back by hand",
                    structureDir, aside, restoreFailure);
        }
    }

    // forceDelete throws on the very conditions that break the write step (a full disk, a revoked
    // permission), so it is attached to the real failure instead of replacing the cause the operator needs
    // to see. With nothing to attach it to, a leftover only wastes disk, so it must not fail the document.
    private static void discard(Path directory, Throwable failure) {
        if (Files.notExists(directory)) {
            return;
        }
        try {
            FileUtils.forceDelete(directory.toFile());
        } catch (IOException cleanupFailure) {
            if (failure == null) {
                LOGGER.warn("cannot remove {}, leaving it behind", directory, cleanupFailure);
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static void write(Path pagesDir, int pageNumber, Page page) throws IOException {
        Files.writeString(pagesDir.resolve(ArtifactPath.pageFilename(pageNumber, "xhtml")),
                page.xhtml(), StandardCharsets.UTF_8);
        Files.writeString(pagesDir.resolve(ArtifactPath.pageFilename(pageNumber, "md")),
                page.markdown(), StandardCharsets.UTF_8);
    }

    private static String tikaVersion() {
        return Tika.getString().replace(TIKA_PREFIX, "").strip();
    }
}
