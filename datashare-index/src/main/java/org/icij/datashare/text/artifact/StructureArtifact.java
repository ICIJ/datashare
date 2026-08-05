package org.icij.datashare.text.artifact;

import org.apache.commons.io.FileUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
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
import java.util.stream.Stream;

/** The structure artifact: a per-page Tika rendering written as page-NNNN.xhtml (sanitized, the
 *  source of truth) and page-NNNN.md (derived from it) under the document's structure/ dir. */
public class StructureArtifact implements Artifact {
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureArtifact.class);
    private static final ArtifactType TYPE = ArtifactType.STRUCTURE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";
    // Read once: Tika.getString() re-reads a jar resource, and taskInput() is called per document.
    private static final String TIKA_VERSION = Tika.getString().replace(TIKA_PREFIX, "").strip();
    // Staging dirs live in the document dir, so every rename below is same-filesystem and therefore
    // atomic, and are named so neither the manifest nor the serving side ever reads them.
    private static final String STAGING_DIR_PREFIX = ".structure-";
    private static final String REPLACED_SUFFIX = ".replaced";
    // The message AutoDetectParser gives a SecureContentHandler refusal (see isZipBombGuard).
    private static final String ZIP_BOMB_MESSAGE = "Zip bomb detected!";

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
        return Map.of("pipeline", "tika", "version", TIKA_VERSION, "datashare", DatashareVersion.VALUE);
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        try (InputStream source = context.sources().getSource(context.project(), context.document())) {
            List<Page> pages = parse(source, context.document());
            if (rendersNoText(pages)) {
                return noPayloadFor(context.docArtifactDir());
            }
            writePages(context.docArtifactDir(), pages);
            return ManifestEntry.paginated(taskInput(), pages.size());
        } catch (UnparseableContentException unparseable) {
            // The producer records this one as processed with no payload too, so the payload goes now.
            discardPayload(context.docArtifactDir());
            throw unparseable;
        } catch (ArtifactException alreadyClassified) {
            // Raised by parse(), so wrapping it again would only double its message.
            throw alreadyClassified;
        } catch (Exception failure) {
            throw new ArtifactException("structure extraction failed for " + context.document().getId(), failure);
        }
    }

    // With OCR off a scanned page or a standalone image renders empty. Recording that as a complete page
    // set would leave a consumer unable to tell "no structure" from "page one is blank", so nothing is
    // recorded, and any payload an earlier release wrote goes with it: the manifest would otherwise say
    // there is nothing to serve while a reader listing the directory still finds those pages.
    private ManifestEntry noPayloadFor(Path docArtifactDir) {
        discardPayload(docArtifactDir);
        return ManifestEntry.empty(taskInput());
    }

    private static boolean rendersNoText(List<Page> pages) {
        return pages.stream().allMatch(page -> page.markdown().isBlank());
    }

    /**
     * Parses into pages, sorting a failure into one of three buckets, because what a parse failure means
     * decides whether the document is ever looked at again:
     * <ul>
     *   <li>fatal: a broken Tika configuration fails every document, so it ends the run instead of being
     *       counted once per document (ArtifactTask rethrows it where it rethrows an Error);</li>
     *   <li>document-level retryable: this side going wrong, so the document fails and a later run tries
     *       again. Tika's own memory limit belongs here (the next run can have more heap) and so does its
     *       zip-bomb guard, a known false positive on these corpora (extract-lib had to relax its nesting
     *       depth for them). An IOException is not caught at all, which puts a stalled mount and a
     *       truncated read in this bucket too;</li>
     *   <li>document-level terminal: content no parser will read, recorded as an empty entry rather than
     *       re-parsed on every run.</li>
     * </ul>
     * A cancelled parse also arrives as a TikaException, so this is not the last word: the producer asks
     * whether the run was cancelled before it records anything.
     */
    private List<Page> parse(InputStream source, Document document) throws IOException, ArtifactException {
        try {
            return extractor.extract(source, document.getContentType());
        } catch (TikaConfigException fatal) {
            throw new ArtifactConfigurationException(fatal);
        } catch (TikaException | SAXException failure) {
            if (isRetryable(failure)) {
                throw new ArtifactException("structure extraction failed for " + document.getId(), failure);
            }
            throw new UnparseableContentException(document.getId(), failure);
        }
    }

    // Tika raises its own limits and its zip-bomb guard as the same TikaException the content itself
    // raises, so the two document-level buckets are told apart here rather than by catch order.
    static boolean isRetryable(Throwable failure) {
        return failure instanceof TikaMemoryLimitException || isZipBombGuard(failure);
    }

    // SecureContentHandler.SecureSAXException is private to Tika and AutoDetectParser converts it into a
    // plain TikaException, so this message is the only thing left to recognise the guard by.
    private static boolean isZipBombGuard(Throwable failure) {
        return failure instanceof TikaException && ZIP_BOMB_MESSAGE.equals(failure.getMessage());
    }

    // The document's artifact dir is created rather than assumed: with raw out of the selection nothing
    // else creates it, so an ARTIFACT run over a fresh artifactDir would fail on every document.
    static void writePages(Path docArtifactDir, List<Page> pages) throws IOException {
        Files.createDirectories(docArtifactDir);
        reclaimReplacedPayloads(docArtifactDir);
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

    // A leftover holding pen means a delete failed, and its name is unique per invocation, so nothing
    // else would ever reclaim it: the document would grow by a full page set on every re-produce. Only
    // ".replaced" pens are swept, never a staging directory, which a concurrent producer of the same
    // digest may be writing into right now.
    private static void reclaimReplacedPayloads(Path docArtifactDir) throws IOException {
        try (Stream<Path> entries = Files.list(docArtifactDir)) {
            entries.filter(entry -> isReplacedPayload(entry.getFileName().toString()))
                    .forEach(leftover -> discard(leftover, null));
        }
    }

    private static boolean isReplacedPayload(String name) {
        return name.startsWith(STAGING_DIR_PREFIX) && name.endsWith(REPLACED_SUFFIX);
    }

    // Losing the payload is the point: it is regenerable, and one the manifest does not account for is
    // still served by any reader that lists the directory.
    private static void discardPayload(Path docArtifactDir) {
        discard(ArtifactPath.structureDir(docArtifactDir), null);
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
}
