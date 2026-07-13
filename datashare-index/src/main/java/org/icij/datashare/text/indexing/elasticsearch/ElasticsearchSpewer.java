package org.icij.datashare.text.indexing.elasticsearch;

import com.google.inject.Inject;
import org.icij.datashare.*;
import org.icij.datashare.extract.DocumentCollectionFactory;
import org.icij.datashare.text.*;
import org.icij.datashare.text.artifact.ManifestRecorder;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.LanguageGuesser;
import org.icij.extract.document.TikaDocument;
import org.icij.extract.ocr.OCRParser;
import org.icij.extract.queue.DocumentQueue;
import org.icij.spewer.FieldNames;
import org.icij.spewer.Spewer;
import org.icij.task.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.System.currentTimeMillis;
import java.util.HashMap;
import java.util.Map;

import static java.util.Optional.ofNullable;
import static org.apache.tika.metadata.HttpHeaders.*;
import static org.icij.datashare.PropertiesProvider.DEFAULT_PROJECT_OPT;
import static org.icij.datashare.cli.DatashareCliOptions.DEFAULT_DEFAULT_PROJECT;
import static org.icij.datashare.text.Hasher.shorten;

public class ElasticsearchSpewer extends Spewer implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchSpewer.class);
    public static final String DEFAULT_VALUE_UNKNOWN = "unknown";
    // Largest char[] a String can be backed by (Integer.MAX_VALUE - 8). Used as the hard ceiling
    // when maxContentLength is disabled (-1): it is exactly the length at which the previous
    // toString(reader) call blew up with "Required array length 2147483639 + 9 is too large", so
    // stopping here avoids that OOM while never dropping content that could otherwise be indexed.
    private static final int MAX_CONTENT_LENGTH = Integer.MAX_VALUE - 8;
    private static final int READ_CHUNK_SIZE = 8192;

    static final String PST_ATTACHMENT_RECOVERY = "tika:pst_attachment_recovery";
    static final String PST_EXPECTED = "tika:pst_expected";
    static final String PST_EMITTED = "tika:pst_emitted";
    static final String PST_UNRECOVERED = "tika:pst_attachments_unrecovered";
    // Mirrors extract-lib ResilientOutlookPSTParser's UNKNOWN_COUNT reconciliation sentinel,
    // emitted when descriptor enumeration failed so loss is undetectable. Intentionally a separate
    // constant from DEFAULT_VALUE_UNKNOWN: it must track the parser's sentinel, not datashare's
    // display default, even though both currently read "unknown".
    private static final String PST_UNKNOWN = "unknown";

    private static Document.RecoveryStatus parseChildRecoveryStatus(TikaDocument document) {
        String raw = document.getMetadata().get(PST_ATTACHMENT_RECOVERY);
        if (raw == null) {
            return null;
        }
        try {
            return Document.RecoveryStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            logger.warn("unknown pst_attachment_recovery value '{}' on {}", raw, document.getId());
            return null;
        }
    }

    private static Integer parsePstCount(String raw) {
        if (raw == null || PST_UNKNOWN.equals(raw)) {
            return null;
        }
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void applyParentRollup(DocumentBuilder builder, TikaDocument document) {
        String expectedRaw = document.getMetadata().get(PST_EXPECTED);
        String emittedRaw = document.getMetadata().get(PST_EMITTED);
        // The parser always records expected+emitted together (reconciliation runs on every PST),
        // so both absent means this is not a PST root. A half-present pair (only one set) is treated
        // as unknown below and rolls up to LOSSY rather than being silently trusted.
        if (expectedRaw == null && emittedRaw == null) {
            return; // not a PST root
        }
        Integer expected = parsePstCount(expectedRaw);
        Integer emitted = parsePstCount(emittedRaw);
        Integer unrecovered = parsePstCount(document.getMetadata().get(PST_UNRECOVERED));
        builder.withPstCounts(expected, emitted, unrecovered);

        boolean unknown = PST_UNKNOWN.equals(expectedRaw) || expected == null || emitted == null;
        Document.RecoveryStatus rollup;
        if (unknown || expected > emitted) {
            rollup = Document.RecoveryStatus.LOSSY;
        } else if (unrecovered != null && unrecovered > 0) {
            rollup = Document.RecoveryStatus.PARTIAL;
        } else {
            // No message loss and no unrecovered attachments. A null unrecovered count is the normal
            // case for a healthy classic (non-OST-2013) PST, where the parser emits no attachment
            // counters at all: that is genuinely COMPLETE, not partial.
            rollup = Document.RecoveryStatus.COMPLETE;
        }
        builder.with(rollup);
    }

    private final Indexer indexer;
    private final LanguageGuesser languageGuesser;
    private final int maxContentLength;
    private final Hasher digestAlgorithm;
    private final DocumentQueue<String> outputQueue;
    public String indexName;
    private ManifestRecorder manifestRecorder;

    @Inject
    public ElasticsearchSpewer(final Indexer indexer, DocumentCollectionFactory<String> outputQueueFactory, LanguageGuesser languageGuesser, final FieldNames fields,
                               final PropertiesProvider propertiesProvider) {
        super(fields);
        this.indexer = indexer;
        this.languageGuesser = languageGuesser;
        this.maxContentLength = getMaxContentLength(propertiesProvider);
        this.digestAlgorithm = getDigestAlgorithm(propertiesProvider);
        this.outputQueue = outputQueueFactory.createQueue(new PipelineHelper(propertiesProvider).getOutputQueueNameFor(Stage.INDEX), String.class);
        this.indexName = propertiesProvider.get(DEFAULT_PROJECT_OPT).orElse(DEFAULT_DEFAULT_PROJECT);
        logger.info("spewer defined with {}", indexer);
    }

    @Override
    protected void writeDocument(TikaDocument doc, TikaDocument parent, TikaDocument root, int level) throws IOException {
        if (root != null && root.isDuplicate()) {
            logger.debug("root document {} is duplicate, skipping {}", root.getId(), doc.getId());
            return;
        }
        long before = currentTimeMillis();
        String docType = parent == null ? "Document" : "Child";
        if (parent == null && isDuplicate(doc.getId(), doc.getPath())) {
            doc.setDuplicate(true);
            copy(doc.getReader(), OutputStream.nullOutputStream()); // flush document content reader
            indexer.add(indexName, new Duplicate(doc.getPath(), doc.getId(), digestAlgorithm));
            docType = "Duplicate";
        } else {
            Document document = getDocument(doc, root, parent, (short) level);
            indexer.add(indexName, document);
            if (manifestRecorder != null) {
                try {
                    manifestRecorder.record(document);
                } catch (Exception e) {
                    // Mirror the ARTIFACT stage (ArtifactProducer.produce): a manifest-write failure
                    // must not abort indexing of an already-indexed document, which would orphan a
                    // container's children. Log and continue.
                    logger.error("failed to record artifact manifest for {}", document.getId(), e);
                }
            }
            String queueEntry = DocReference.fromDocument(document).toQueueEntry();
            if (!outputQueue.offer(queueEntry)) {
                logger.warn("cannot offer {} to queue {}", queueEntry, outputQueue.getName());
            }
        }
        logger.info("{} {} added to elasticsearch in {}ms: {}", docType,
                shorten(doc.getId(), 4), currentTimeMillis() - before, doc);
    }

    @Override
    public boolean writeRootStub(TikaDocument root, long writtenChildren) throws IOException {
        // The streaming parse aborted (timeout/cancel/OOM) after some embedded children had already
        // been indexed, but before the real root was written. Index a minimal, contentless root so
        // those children are not orphaned under a missing root; mark it PARTIAL and record how many
        // children were indexed. A later re-run of the ARTIFACT/INDEX stage replaces this stub with
        // the fully-parsed root. No content is read here: the parse is gone and its reader/temp files
        // may already be torn down.
        if (root.isDuplicate()) {
            return false;
        }
        // Never clobber an already-indexed root: a prior successful run may have indexed this container
        // fully (content, search text, its real recovery status), and overwriting it with a contentless
        // PARTIAL stub would be data loss. If the root already exists, its children are not orphaned, so
        // there is nothing to do. (A stub left by an earlier aborted run is likewise kept as-is.)
        if (isDuplicate(root.getId())) {
            logger.debug("aborted parse: root {} already indexed, keeping it; not writing PARTIAL stub", shorten(root.getId(), 4));
            return false;
        }
        String contentType = baseContentType(ofNullable(root.getMetadata().get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN));
        Document document = DocumentBuilder.createDoc(root.getId())
                .with(root.getPath())
                .with(Document.Status.INDEXED)
                .with(getMetadata(root))
                .with("") // contentless: the parse aborted, no body text was extracted for the root
                .ofContentType(contentType)
                .with(ContentTypeCategory.fromContentType(contentType))
                .withContentLength(parseContentLength(root))
                .withExtractionLevel((short) 0)
                .with(Document.RecoveryStatus.PARTIAL)
                .withNbChildrenEmitted((int) Math.min(writtenChildren, Integer.MAX_VALUE))
                .build();
        indexer.add(indexName, document);
        logger.warn("aborted parse: wrote PARTIAL root stub {} with {} indexed child(ren): {}",
                shorten(root.getId(), 4), writtenChildren, root);
        return true;
    }

    @Override
    public void finalizeRoot(TikaDocument root, long writtenChildren) throws IOException {
        // The container parsed to completion; its root was already indexed with content during the parse.
        // Now that the child count is final, record it and (for non-PST containers) mark the root
        // complete. A partial update preserves the already-indexed content/language/status. PST roots
        // already carry their COMPLETE/PARTIAL/LOSSY rollup from the initial write (applyParentRollup),
        // so their recoveryStatus is left untouched here.
        updateRootChildCount(root, writtenChildren, true);
        logger.info("finalized container root {} as complete with {} indexed child(ren): {}",
                shorten(root.getId(), 4), writtenChildren, root);
    }

    @Override
    public void finalizeAbortedRoot(TikaDocument root, long writtenChildren) throws IOException {
        // The parse aborted after the early stub was written but before the real root write. Refresh the
        // child count on the still-PARTIAL stub via a partial update, WITHOUT touching recoveryStatus, so
        // the root stays PARTIAL (the container was not fully processed). A later re-run replaces the stub
        // with the fully-parsed root.
        updateRootChildCount(root, writtenChildren, false);
        logger.warn("aborted parse: refreshed PARTIAL root stub {} child count to {}: {}",
                shorten(root.getId(), 4), writtenChildren, root);
    }

    // Partial (doc-merge) update of the container root's child count, int-clamped, preserving the
    // already-indexed content/language/status. markComplete flips a non-PST root to COMPLETE (PST roots
    // keep their applyParentRollup rollup either way); the aborted path passes false so the root stays
    // PARTIAL.
    private void updateRootChildCount(TikaDocument root, long writtenChildren, boolean markComplete) throws IOException {
        Map<String, Object> fields = new HashMap<>();
        fields.put("nbChildrenEmitted", (int) Math.min(writtenChildren, Integer.MAX_VALUE));
        if (markComplete) {
            boolean isPstRoot = root.getMetadata().get(PST_EXPECTED) != null || root.getMetadata().get(PST_EMITTED) != null;
            if (!isPstRoot) {
                fields.put("recoveryStatus", Document.RecoveryStatus.COMPLETE.toString());
            }
        }
        indexer.update(indexName, root.getId(), fields);
    }

    // Tika's Content-Type may carry parameters after ';' (e.g. "text/plain;charset=utf-8"); keep the
    // media-type prefix. Uses substring rather than split(";")[0] because a value of only ";" splits to
    // an empty array and [0] would throw -- and a crash here would abort the (stub or real) root write
    // and orphan already-streamed children, exactly what these writes exist to prevent.
    private static String baseContentType(String rawContentType) {
        int semicolon = rawContentType.indexOf(';');
        return semicolon >= 0 ? rawContentType.substring(0, semicolon) : rawContentType;
    }

    // Defensive parse: a non-numeric Content-Length must not abort the stub write (that would leave the
    // already-indexed children orphaned, which is exactly what the stub prevents). Unknown length -> -1.
    private static long parseContentLength(TikaDocument document) {
        try {
            return Long.parseLong(ofNullable(document.getMetadata().get(CONTENT_LENGTH)).orElse("-1"));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private boolean isDuplicate(String docId) throws IOException {
        return indexer.exists(indexName, docId);
    }

    private boolean isDuplicate(String docId, Path path) throws IOException {
        // The report map is already in charge of avoiding a reindexing of the same document.
        // For this reason, we consider a document to be a "Duplicate" of another document only
        // if it has the same id (based on its hash) and a different path. This allows us
        // to re-index an existing document while ensuring duplicates are detected.
        return !indexer.exists(indexName, docId, path) && isDuplicate(docId);
    }

    Document getDocument(TikaDocument document, TikaDocument root, TikaDocument parent, short level) throws IOException {
        Charset charset = Charset.isSupported(ofNullable(document.getMetadata().get(CONTENT_ENCODING)).orElse(DEFAULT_VALUE_UNKNOWN)) ?
                Charset.forName(document.getMetadata().get(CONTENT_ENCODING)) : StandardCharsets.US_ASCII;
        String contentType = baseContentType(ofNullable(document.getMetadata().get(CONTENT_TYPE)).orElse(DEFAULT_VALUE_UNKNOWN));
        DocumentBuilder builder = DocumentBuilder.createDoc(document.getId())
                .with(document.getPath())
                .with(Document.Status.INDEXED)
                .with(getMetadata(document))
                .ofContentType(contentType)
                .with(ContentTypeCategory.fromContentType(contentType))
                .withContentLength(Long.parseLong(ofNullable(document.getMetadata().get(CONTENT_LENGTH)).orElse("-1")))
                .with(charset)
                .withExtractionLevel(level)
                .withOcrParser(document.getMetadata().get(OCRParser.OCR_PARSER))
                .with(parseChildRecoveryStatus(document));

        String content = readContent(document);
        if (document.getLanguage() == null) {
            builder.with(languageGuesser.guess(content));
        } else  {
            builder.with(Language.parse(document.getLanguage()));
        }
        builder.with(content);

        if (parent != null) {
            builder.withParentId(parent.getId());
            builder.withRootId(root.getId());
        }
        applyParentRollup(builder, document);
        return builder.build();
    }

    // Reads the extracted text bounded to maxContentLength characters. The previous
    // toString(document.getReader()) copied the whole reader into an unbounded StringWriter and
    // only truncated afterwards, so multi-GB extracted text (e.g. a zip bomb's contents) overran
    // the 2^31-1 char-array cap and threw OutOfMemoryError before the cap could ever apply. Here
    // we never buffer more than the cap: reading stops as soon as the limit is reached.
    String readContent(TikaDocument document) throws IOException {
        // A configured maxContentLength can be as high as Integer.MAX_VALUE (see getMaxContentLength),
        // and maxContentLength == -1 disables the configured cap entirely, but in both cases we still
        // refuse to buffer past the largest array a String can hold, so clamp the effective limit in
        // every branch rather than only guarding -1. (-1 stays "index everything up to that array
        // cap": with no configured limit a single multi-GB document is bounded only by the heap, by
        // design; operators cap memory by setting a finite maxContentLength.)
        final int limit = Math.min(maxContentLength == -1 ? MAX_CONTENT_LENGTH : maxContentLength, MAX_CONTENT_LENGTH);
        final StringBuilder content = new StringBuilder();
        final char[] chunk = new char[READ_CHUNK_SIZE];
        // The reader is owned and closed by Spewer.write/writeTree (see closeReaderQuietly), so we
        // only read from it here; leaving it partially consumed on truncation is fine, close still
        // releases any spilled temp files.
        final Reader reader = document.getReader();
        // The old code did toString(reader).trim() *before* applying the cap, so leading whitespace
        // never counted against maxContentLength. We reproduce that: leading whitespace (blank pages,
        // OCR page breaks preceding the body) is skipped without buffering, however many chunks it
        // spans, until the body appears -- so a document that opens with a run of blank lines longer
        // than the cap still gets its real text indexed instead of an all-whitespace buffer that
        // trims to empty. The skip is bounded by the same array cap as the buffer, so a reader that
        // only ever yields whitespace still terminates.
        boolean bodyStarted = false;
        long skippedLeadingWhitespace = 0;
        boolean truncated = false;
        int read;
        while ((read = reader.read(chunk)) != -1) {
            int offset = 0;
            if (!bodyStarted) {
                while (offset < read && chunk[offset] <= ' ') {
                    offset++;
                }
                if (offset == read) {
                    skippedLeadingWhitespace += offset;
                    if (skippedLeadingWhitespace >= MAX_CONTENT_LENGTH) {
                        break;
                    }
                    continue;
                }
                bodyStarted = true;
            }
            final int available = read - offset;
            final int remaining = limit - content.length();
            if (available <= remaining) {
                content.append(chunk, offset, available);
            } else {
                content.append(chunk, offset, remaining);
                // The cap is reached, but only report truncation if real (non-whitespace) content
                // still follows: trailing whitespace that merely overflows the window is stripped by
                // trim() and did not count as truncation in the old toString(reader).trim() path. We
                // look only at the chars already read into the current chunk and deliberately do not
                // pull more from the reader for this cosmetic flag -- otherwise a document capped
                // right where an unbounded run of trailing whitespace begins would be scanned to EOF,
                // reintroducing the unbounded read this class exists to avoid. Real content that only
                // resumes in a later chunk is under-reported as non-truncated, which at worst drops a
                // warning log line.
                truncated = hasNonWhitespaceRemaining(chunk, offset + remaining, read);
                break;
            }
        }
        if (truncated) {
            logger.warn("document id {} extracted text will be truncated to {} characters", document.getId(), limit);
        }
        return trim(content);
    }

    // After the content cap has been reached, reports whether any non-whitespace character remains in
    // the already-read tail of the current chunk (indices [from, read)). It reads and buffers nothing
    // further, so it can never reintroduce the unbounded read this class exists to avoid, even when a
    // document is capped exactly where an unbounded run of trailing whitespace begins.
    private static boolean hasNonWhitespaceRemaining(char[] chunk, int from, int read) {
        for (int i = from; i < read; i++) {
            if (chunk[i] > ' ') {
                return true;
            }
        }
        return false;
    }

    // Reproduces String.trim() semantics (strips code points <= U+0020 from both ends) directly on
    // the bounded buffer, so we don't allocate a second copy of the whole extracted text.
    private static String trim(StringBuilder content) {
        int start = 0;
        int end = content.length();
        while (start < end && content.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && content.charAt(end - 1) <= ' ') {
            end--;
        }
        return content.substring(start, end);
    }

    int getMaxContentLength(PropertiesProvider propertiesProvider) {
        return (int) Math.min(HumanReadableSize.parse(propertiesProvider.get("maxContentLength").orElse("-1")), Integer.MAX_VALUE);
    }

    private Hasher getDigestAlgorithm(PropertiesProvider propertiesProvider) {
        return Hasher.parse(propertiesProvider.get("digestAlgorithm")
                .orElse(Entity.DEFAULT_DIGESTER.name())).orElse(Entity.DEFAULT_DIGESTER);
    }

    @Override
    public Spewer configure(Options<String> options) {
        super.configure(options);
        setIndex(options.valueIfPresent("projectName").orElse(options.get(DEFAULT_PROJECT_OPT).value().get()));
        return this;
    }

    /** Opt-in: when set, records the raw manifest entry for each indexed document (INDEX-time
     *  artifact generation). Null by default, so indexing is unchanged unless --artifacts is set. */
    public ElasticsearchSpewer setManifestRecorder(ManifestRecorder manifestRecorder) {
        this.manifestRecorder = manifestRecorder;
        return this;
    }

    public Spewer createIndexIfNotExists() throws IOException {
        indexer.createIndex(indexName);
        return this;
    }

    public Spewer createIndexIfNotExists(String indexName) throws IOException {
        setIndex(indexName);
        createIndexIfNotExists();
        return this;
    }

    @Override
    public void close() throws Exception {
        outputQueue.put("POISON");
    }

    private void setIndex(String indexName) {
        this.indexName = indexName;
    }
}
