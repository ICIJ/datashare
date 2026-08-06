package org.icij.datashare.text.artifact;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.TikaMemoryLimitException;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.icij.datashare.text.structure.StructureMarkdownExtractor.Page;
import org.icij.datashare.utils.AtomicDirectorySwap;
import org.icij.datashare.utils.BuildVersions;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.icij.datashare.text.nlp.DocumentMetadataConstants.RESOURCE_NAME_KEY;

/** The structure artifact: a per-page Tika rendering written as page-NNNN.xhtml (sanitized, the
 *  source of truth) and page-NNNN.md (derived from it) under the document's structure/ dir. */
public class StructureArtifact implements Artifact {
    private static final ArtifactType TYPE = ArtifactType.STRUCTURE;
    // Tika.getString() returns "Apache Tika <version>"; extract-lib strips the same prefix.
    private static final String TIKA_PREFIX = "Apache Tika";
    // Read once: Tika.getString() re-reads a jar resource, and taskInput() is called per document.
    private static final String TIKA_VERSION = Tika.getString().replace(TIKA_PREFIX, "").strip();
    // The message AutoDetectParser gives a SecureContentHandler refusal (see isZipBombGuard).
    private static final String ZIP_BOMB_MESSAGE = "Zip bomb detected!";

    private final StructureMarkdownExtractor extractor = new StructureMarkdownExtractor();

    @Override
    public ArtifactType type() {
        return TYPE;
    }

    @Override
    public Map<String, Object> taskInput() {
        // A fingerprint of the code that made the bytes, so skip-if-current sees pages an earlier release
        // rendered as stale: Tika renders the XHTML, extract-lib owns the parser set (the resilient PST
        // one), and the datashare version covers what this class decides (page grouping, safelist,
        // flexmark options), which no dependency version tracks. Deliberately conservative: any datashare
        // release makes every structure artifact stale, so the next ARTIFACT run re-extracts a corpus.
        return Map.of("pipeline", "tika", "version", TIKA_VERSION,
                "extract", BuildVersions.EXTRACT, "datashare", BuildVersions.DATASHARE);
    }

    @Override
    public ManifestEntry produce(ArtifactContext context) throws ArtifactException {
        try (InputStream source = context.sources().getSource(context.project(), context.document())) {
            // The parser's output is served as it comes, blank pages included: with OCR off a scanned page
            // renders empty, and "the parser found no text here" is a different thing from the EMPTY of a
            // raw entry, which means there is nothing at this path to serve at all.
            List<Page> pages = parse(source, context.document());
            writePages(context.docArtifactDir(), pages);
            return ManifestEntry.paginated(taskInput(), pages.size());
        } catch (UnreadableContentException unreadable) {
            // The producer records this one as processed with no payload too, so the payload goes now.
            discardPayload(context.docArtifactDir());
            throw unreadable;
        } catch (ArtifactException alreadyClassified) {
            // Raised by parse(), so wrapping it again would only double its message.
            throw alreadyClassified;
        } catch (Exception failure) {
            throw new ArtifactException("structure extraction failed for " + context.document().getId(), failure);
        }
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
            return extractor.extract(source, document.getContentType(), resourceName(document));
        } catch (TikaConfigException fatal) {
            throw new ArtifactConfigurationException(fatal);
        } catch (TikaException | SAXException failure) {
            if (isRetryable(failure)) {
                throw new ArtifactException("structure extraction failed for " + document.getId(), failure);
            }
            throw new UnreadableContentException(document.getId(), failure);
        }
    }

    // Tika raises its own limits and its zip-bomb guard as the same TikaException the content itself
    // raises, so the two document-level buckets are told apart here rather than by catch order.
    static boolean isRetryable(Throwable failure) {
        return failure instanceof TikaMemoryLimitException || isZipBombGuard(failure);
    }

    // The document's own name, from the Tika metadata rather than from the path: an embedded document
    // carries its container's path, so the path would hand a mail attachment's bytes the name of the
    // archive they came out of.
    private static String resourceName(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object resourceName = metadata == null ? null : metadata.get(RESOURCE_NAME_KEY);
        return resourceName == null ? null : resourceName.toString();
    }

    // SecureContentHandler.SecureSAXException is private to Tika and AutoDetectParser converts it into a
    // plain TikaException, so this message is the only thing left to recognise the guard by.
    private static boolean isZipBombGuard(Throwable failure) {
        return failure instanceof TikaException && ZIP_BOMB_MESSAGE.equals(failure.getMessage());
    }

    // The swap itself is not structure-specific and lives in AtomicDirectorySwap, which creates the
    // document's artifact dir on the way: with raw out of the selection nothing else creates it, so an
    // ARTIFACT run over a fresh artifactDir would otherwise fail on every document.
    static void writePages(Path docArtifactDir, List<Page> pages) throws IOException {
        AtomicDirectorySwap.replace(ArtifactPath.structureDir(docArtifactDir), staging -> {
            for (int index = 0; index < pages.size(); index++) {
                write(staging, index + 1, pages.get(index));
            }
        });
    }

    // Losing the payload is the point: it is regenerable, and one the manifest does not account for is
    // still served by any reader that lists the directory.
    private static void discardPayload(Path docArtifactDir) {
        AtomicDirectorySwap.discard(ArtifactPath.structureDir(docArtifactDir));
    }

    private static void write(Path pagesDir, int pageNumber, Page page) throws IOException {
        Files.writeString(pagesDir.resolve(ArtifactPath.pageFilename(pageNumber, "xhtml")),
                page.xhtml(), StandardCharsets.UTF_8);
        Files.writeString(pagesDir.resolve(ArtifactPath.pageFilename(pageNumber, "md")),
                page.markdown(), StandardCharsets.UTF_8);
    }
}
