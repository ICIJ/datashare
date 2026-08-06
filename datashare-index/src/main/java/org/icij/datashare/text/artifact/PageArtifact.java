package org.icij.datashare.text.artifact;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.utils.BuildVersions;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.Extractor;
import org.icij.task.Options;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.icij.datashare.cli.DatashareCliOptions.OCR_OPT;

/** The page artifact: a document's paginated PLAIN extracted text, written as a single
 *  pages/content.txt whose per-page half-open byte ranges live in the manifest entry. */
public class PageArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.PAGE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";
    // Read once: Tika.getString() re-reads a jar resource, and taskInput() is called per document.
    private static final String TIKA_VERSION = Tika.getString().replace(TIKA_PREFIX, "").strip();

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
        // A fingerprint of the code that made the bytes, as StructureArtifact records: Tika renders the
        // text, extract-lib owns the parser set and the page splitting, and the datashare version covers
        // what this class decides. The run's OCR setting, not the per-document one: taskInput() gets no
        // document, and its contract keeps per-document state out so the same doc compares equal across
        // batches.
        return Map.of("pipeline", "tika", "version", TIKA_VERSION, "ocr", ocrEnabled(),
                "extract", BuildVersions.EXTRACT, "datashare", BuildVersions.DATASHARE);
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
        } catch (ArtifactException alreadyClassified) {
            // Raised by extractPages(), so wrapping it again would only double its message.
            throw alreadyClassified;
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
        // isRootDocument(), not the extraction level alone: it is where the rootId and the level are
        // required to agree, so a document whose labels disagree takes the embedded path below, as it
        // does in SourceExtractor and RawArtifact, instead of being paginated from its container's file.
        if (document.isRootDocument()) {
            return document.getPath();
        }
        Path raw = context.docArtifactDir().resolve(ArtifactPath.RAW_FILE);
        // Zero-byte, not just absent: extract-lib records an empty raw payload for embeds whose
        // bytes it could not read (the same rule SourceExtractor.hasCachedEmbeddedSource applies), and
        // pagination would otherwise accept it as a source, find no page divs, and record a terminal
        // EMPTY that skip-if-current never retries.
        if (Files.notExists(raw) || Files.size(raw) == 0) {
            context.sources().getSource(context.project(), document).close();
        }
        if (Files.notExists(raw) || Files.size(raw) == 0) {
            throw new IOException("no raw payload to paginate for embedded document " + document.getId());
        }
        return raw;
    }

    // One Extractor per document, as the live endpoint does per request: disableOcr() is one-way, so
    // a document indexed without OCR cannot share an Extractor with one indexed with it. embedOutput
    // is deliberately left unset: this producer writes its own payload and nothing else.
    private List<String> extractPages(Document document, Path source) throws IOException, ArtifactException {
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
        } catch (IOException failure) {
            throw classify(document, failure);
        }
    }

    /**
     * Sorts a parse failure into the three buckets {@link StructureArtifact} sorts its own into, since
     * what a failure means decides whether the document is ever looked at again: a broken Tika
     * configuration ends the run, Tika's memory limit and its zip-bomb false positive are worth another
     * one, and content no parser can read is recorded as empty rather than re-parsed forever.
     * <p>
     * Read off the cause chain rather than by catch type: extract-lib parses on its own thread and hands
     * whatever it caught back as the cause of an IOException (ParsingReaderWithContentHandler#read), so
     * every bucket arrives here as the same exception. An IOException with no parse failure under it (a
     * stalled mount, a truncated read) stays retryable, as it does there.
     */
    private static ArtifactException classify(Document document, IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TikaConfigException) {
                throw new ArtifactConfigurationException(cause);
            }
            if (cause instanceof TikaException || cause instanceof SAXException) {
                return StructureArtifact.isRetryable(cause) ? retryable(document, failure)
                        : new UnreadableContentException(document.getId(), cause);
            }
            // A custom exception can return itself from getCause(), which would spin this walk forever.
            if (cause == cause.getCause()) {
                break;
            }
        }
        return retryable(document, failure);
    }

    private static ArtifactException retryable(Document document, IOException failure) {
        return new ArtifactException("page extraction failed for " + document.getId(), failure);
    }

    // Writes the whole payload to a unique temp file in the same directory (so the move is
    // same-filesystem and therefore atomic), then swaps it in. A failure before the move leaves the
    // previous content.txt in place, still matching its complete manifest entry. A failure after the
    // move (the caller's repository.put, e.g. a manifest lock timeout) can instead leave the new
    // content.txt live while the manifest still describes the previous one; guarding against that is
    // a consumer-side concern (#2228), not this method's. Offsets are the byte counts actually
    // written, so the recorded ranges cannot disagree with the file: half-open [start, end),
    // contiguous, first start 0, last end == length.
    private static List<long[]> writePages(ArtifactContext context, List<String> pages) throws IOException {
        Path content = ArtifactPath.pagesContent(context.docArtifactDir());
        Files.createDirectories(content.getParent());
        // A unique name per attempt: two producers racing on the same document (shared artifactDir
        // across hosts, or the same doc queued twice) must never open the same temp path, or the
        // loser's write truncates the winner's file to a hole of NUL bytes mid-write. Built by hand
        // rather than with Files.createTempFile, which creates the file mode rw------- and would
        // carry that restrictive mode onto content.txt through the ATOMIC_MOVE below, instead of the
        // umask default every other artifact file gets.
        Path temp = content.resolveSibling(content.getFileName() + "." + UUID.randomUUID() + ".tmp");
        List<long[]> ranges = new ArrayList<>();
        long offset = 0;
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                for (String page : pages) {
                    byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
                    out.write(bytes);
                    ranges.add(new long[]{offset, offset + bytes.length});
                    offset += bytes.length;
                }
            }
            Files.move(temp, content, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // A half-written temp file must never survive this call, whether the write or the move failed.
            Files.deleteIfExists(temp);
        }
        return ranges;
    }

    private boolean ocrEnabled() {
        return propertiesProvider.get(OCR_OPT).map(Boolean::parseBoolean).orElse(true);
    }
}
