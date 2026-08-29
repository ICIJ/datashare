package org.icij.datashare.text.artifact;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaConfigException;
import org.apache.tika.extractor.DocumentSelector;
import org.apache.tika.metadata.TikaCoreProperties;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Hasher;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.text.structure.StructureMarkdownExtractor;
import org.icij.datashare.utils.AtomicDirectorySwap;
import org.icij.datashare.utils.BuildVersions;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.extractor.EmbedSpawner;
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
        // what this class decides. The run's OCR setting is the best this document-less overload can do;
        // what actually paginated a document is the overload below.
        return taskInput(Artifact.ocrEnabled(propertiesProvider));
    }

    @Override
    public Map<String, Object> taskInput(Document document) {
        // The OCR that made these pages, not the one the run asked for: extractPages() turns OCR off for
        // a document indexed without it, so the run's flag alone stamps OCR-free pages as OCR'd. Re-index
        // that file with OCR on and nothing the fingerprint sees changes (same bytes, same digest, same
        // artifact dir), so skip-if-current serves the OCR-free pages forever. Still config, not data:
        // the same document under the same run config compares equal in any batch.
        return taskInput(Artifact.ocrEnabled(propertiesProvider) && document.getOcrParser() != null);
    }

    private static Map<String, Object> taskInput(boolean ocr) {
        return Map.of("pipeline", "tika", "version", TIKA_VERSION, "ocr", ocr,
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
                // An earlier run's content.txt would otherwise stay on disk, described by no manifest
                // entry and still served by any reader that lists the directory.
                discardPayload(context.docArtifactDir());
                return ManifestEntry.empty(taskInput(document));
            }
            return ManifestEntry.paginated(taskInput(document), writePages(context, pages));
        } catch (ArtifactConfigurationException fatal) {
            // Unchecked, and not an ArtifactException, so the catch-all below would otherwise demote the
            // fatal bucket to one more per-document failure and drain the queue instead of ending the run.
            throw fatal;
        } catch (UnreadableContentException unreadable) {
            // The producer records this one as processed with no payload too, so the payload goes now.
            discardPayload(context.docArtifactDir());
            throw unreadable;
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
    // Package-private, not private: nothing this side can make a real Tika configuration break, so the
    // fatal bucket is only reachable from a test that stands in for this method.
    List<String> extractPages(Document document, Path source) throws IOException, ArtifactException {
        Hasher hasher = Hasher.valueOf(document.getId().length());
        DocumentFactory documentFactory = new DocumentFactory()
                .configure(Options.from(Map.of("digestAlgorithm", hasher.toStringWithoutDash())));
        try (Extractor extractor = new Extractor(documentFactory, Options.from(propertiesProvider.getProperties()))) {
            // Same rule as DocumentResource.getPages: a document indexed without OCR must be
            // paginated without OCR, or its pages would not match its indexed content.
            if (document.getOcrParser() == null) {
                extractor.disableOcr();
            }
            return extractor.extractPages(source, ownTextOf(source));
        } catch (IOException failure) {
            throw classify(document, failure);
        }
    }

    /**
     * Selects the text that belongs to the document being paginated, which is what its pages must hold:
     * they are its indexed content, paginated, so a page saying something the content field does not is
     * a page the reader cannot search for. Three things belong to it, and nothing else does:
     * <ul>
     *   <li>the file being parsed itself. The selector gates the root parse too, not just the parts:
     *       ParsingReaderWithContentHandler#ParsingTask hands the page-splitting handler to the parse
     *       only when the selector accepts it, and a plain handler otherwise, so refusing the root
     *       yields a document with no pages at all. Recognised by name, as DocumentResource recognises
     *       an embed, which also lets in a part named exactly like the file holding it;</li>
     *   <li>an INLINE part, which is what a scanned page's image is. {@link EmbedSpawner#parseEmbedded}
     *       concatenates those into the document being parsed and the content field holds their OCR
     *       text, so the pages must hold it too;</li>
     *   <li>a nameless text or html part, which is how a mail's own body can reach the extractor: it is
     *       not a part anyone detaches, so it has no name of its own. {@link StructureMarkdownExtractor#isOwnBody}
     *       rather than "nameless" alone, so a nameless PST message (message/rfc822, a document of its
     *       own) is still refused. Accepted consequence, as there: a nameless text attachment is
     *       inlined, duplicating text that also has an artifact of its own.</li>
     * </ul>
     * Everything else is a document of its own: spawned by extract-lib, indexed as its own ES document,
     * and given its own page artifact. Refusing it also means never parsing it, since Tika asks before
     * it recurses, so a mail archive's tree is no longer parsed, OCR'd and buffered (64 MB, then spilled
     * to java.io.tmpdir) to produce text that is then thrown away.
     */
    public static DocumentSelector ownTextOf(Path source) {
        String name = source.getFileName().toString();
        return metadata -> {
            String resourceName = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            return name.equals(resourceName)
                    || TikaCoreProperties.EmbeddedResourceType.INLINE.toString()
                            .equals(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE))
                    || StructureMarkdownExtractor.isOwnBody(metadata);
        };
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
     * <p>
     * The fatal bucket is the one exception to reading down the chain: only what the parse thread itself
     * threw counts. Pagination spawns embeds into the same parse, so a TikaConfigException found deeper
     * is one embed's parser going wrong, and ending the whole run on it would drain the queue on a single
     * bad document.
     */
    static ArtifactException classify(Document document, IOException failure) {
        if (failure.getCause() instanceof TikaConfigException) {
            throw new ArtifactConfigurationException(failure.getCause());
        }
        Throwable unreadable = StructureArtifact.unreadableCause(failure);
        return unreadable == null ? retryable(document, failure)
                : new UnreadableContentException(document.getId(), unreadable);
    }

    private static ArtifactException retryable(Document document, IOException failure) {
        return new ArtifactException("page extraction failed for " + document.getId(), failure);
    }

    // Through AtomicDirectorySwap, as the structure artifact writes its own pages: a failure before the
    // swap leaves the previous content.txt in place, still matching its complete manifest entry, and the
    // staging dir it writes into is unique per attempt, so two producers racing on the same document
    // (shared artifactDir across hosts, or the same doc queued twice) cannot write to one another's
    // bytes. A failure after the swap (the caller's repository.put, e.g. a manifest lock timeout) can
    // instead leave the new content.txt live while the manifest still describes the previous one;
    // guarding against that is a consumer-side concern (#2228), not this method's. Offsets are the byte
    // counts actually written, so the recorded ranges cannot disagree with the file: half-open
    // [start, end), contiguous, first start 0, last end == length.
    private static List<long[]> writePages(ArtifactContext context, List<String> pages) throws IOException {
        List<long[]> ranges = new ArrayList<>();
        AtomicDirectorySwap.replace(ArtifactPath.payloadDir(context.docArtifactDir(), TYPE), staging -> {
            long offset = 0;
            try (OutputStream out = Files.newOutputStream(staging.resolve(ArtifactPath.PAGES_CONTENT_FILE))) {
                for (String page : pages) {
                    byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
                    out.write(bytes);
                    ranges.add(new long[]{offset, offset + bytes.length});
                    offset += bytes.length;
                }
            }
        });
        return ranges;
    }

    // Losing the payload is the point: it is regenerable, and one the manifest does not account for is
    // still served by any reader that lists the directory. The whole pages/ dir, since content.txt is
    // all it holds.
    private static void discardPayload(Path docArtifactDir) {
        AtomicDirectorySwap.discard(ArtifactPath.payloadDir(docArtifactDir, TYPE));
    }

}
