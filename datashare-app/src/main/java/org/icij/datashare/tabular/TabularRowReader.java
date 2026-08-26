package org.icij.datashare.tabular;

import org.icij.datashare.text.Document;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.apache.commons.io.IOUtils.closeQuietly;

/**
 * Turns the document a mapping names into rows. Addressing the source by document id rather than by
 * path is what buys embedded sources (a CSV inside a ZIP, a workbook attached to an email) through
 * SourceExtractor, plus the content type and charset Tika already detected at index time. It also
 * means no user-supplied path reaches the filesystem, so this route has no traversal surface at all.
 *
 * Authorization on the project is not checked here: the REST and CLI triggers must route through
 * DocumentSourceAccess, the single decision for serving a document's source bytes, and wiring them to
 * it belongs to #2206. Its DocumentVerifier.isRootDocumentSizeAllowed check is a size guard this
 * class does not apply either, which matters because the tier-2 fallback buffers a whole document
 * several times over.
 */
public class TabularRowReader {
    // "unknown" is what Document.getContentTypeOrDefault returns, and what the spewer stores, when
    // Tika detected no type at all: the document that most needs refining by extension.
    private static final Set<String> GENERIC_TYPES =
            Set.of("text/plain", "application/octet-stream", "unknown");

    // Only the formats a reader claims. Tika 3.3.0 types a .csv named .txt, and every .jsonl, as
    // text/plain, so without this the delimited reader would claim NDJSON files.
    private static final Map<String, String> TYPE_BY_EXTENSION = Map.of(
            "csv", "text/csv",
            "tsv", "text/tab-separated-values",
            "psv", "text/csv",
            "txt", "text/plain",
            "json", "application/json",
            "jsonl", JsonRowSource.NDJSON_CONTENT_TYPE,
            "ndjson", JsonRowSource.NDJSON_CONTENT_TYPE);

    private static final String DELIMITER_METADATA_KEY = "tika_metadata_csv_delimiter";

    private static final String RESOURCE_NAME_METADATA_KEY = "tika_metadata_resourcename";

    // Below its sniffer's confidence threshold Tika types every delimited file as text/plain and
    // records no delimiter, so for the two extensions that name one, the extension is the only thing
    // left that does.
    private static final Map<String, Character> DELIMITER_BY_EXTENSION = Map.of("tsv", '\t', "psv", '|');

    private static final Map<String, Character> DELIMITER_BY_TIKA_NAME = Map.of(
            "comma", ',', "tab", '\t', "pipe", '|', "semicolon", ';');

    private static final List<String> CONTENT_FIELDS = List.of("content", "content_translated");

    private static final List<String> SUPPORTED_CONTENT_TYPES = Stream.of(
                    DelimitedRowSource.SUPPORTED, WorkbookRowSource.SUPPORTED, JsonRowSource.SUPPORTED,
                    TikaTableRowSource.SUPPORTED)
            .flatMap(Set::stream)
            .sorted()
            .toList();

    private final Indexer indexer;
    private final SourceExtractor sourceExtractor;
    private final List<RowSource> readers;

    public TabularRowReader(Indexer indexer, SourceExtractor sourceExtractor) {
        this.indexer = indexer;
        this.sourceExtractor = sourceExtractor;
        // Tika last: it is the tier-2 fallback, and only the types it was confirmed to render as
        // table markup reach it, so it never displaces a tier-1 reader that claims the same type.
        this.readers = List.of(new DelimitedRowSource(), new WorkbookRowSource(), new JsonRowSource(),
                new TikaTableRowSource());
    }

    /**
     * @param rootId the container the document was extracted from, or null for a root document. ES
     *               routes an embedded document by its root, so this cannot be derived here.
     */
    public Stream<Row> rows(Project project, String documentId, String rootId,
                            RowSourceOptions options) throws IOException {
        // Excluding the extracted text, as DocumentSourceAccess does: only four metadata fields are
        // read here, and a large tabular document's content would be a second full copy in heap.
        Document document = indexer.get(project.getName(), documentId,
                rootId == null ? documentId : rootId, CONTENT_FIELDS);
        if (document == null) {
            throw new IllegalArgumentException("no such document in " + project.getName() + ": " + documentId);
        }
        RowSourceOptions resolved = resolve(document, options);
        RowSource reader = select(resolved.contentType());
        InputStream source = sourceExtractor.getSource(project, document);
        try {
            return reader.rows(source, resolved).onClose(() -> closeQuietly(source));
        } catch (IOException | RuntimeException failure) {
            // Swallowing the close: the read already failed or already finished, so a failure to
            // release the source adds nothing the caller can act on and must not mask the real one.
            closeQuietly(source);
            throw failure;
        }
    }

    private RowSourceOptions resolve(Document document, RowSourceOptions options) {
        String filename = filename(document);
        RowSourceOptions resolved = options.withContentType(effectiveContentType(
                options.contentType(), document.getContentTypeOrDefault(), filename));
        if (resolved.charset() == null && document.getContentEncoding() != null) {
            resolved = resolved.withCharset(document.getContentEncoding());
        }
        if (resolved.delimiter() == null) {
            resolved = resolved.withDelimiter(Optional
                    .ofNullable(delimiterFrom(document.getMetadata()))
                    .orElseGet(() -> delimiterByExtension(filename)));
        }
        return resolved;
    }

    /**
     * The name Tika recorded, not the path: an embedded document carries its container's path, so
     * Document.getName would hand records.jsonl inside archive.zip the name of the archive and the
     * extension refinement would go inert on exactly the embedded sources this class exists to reach.
     */
    static String filename(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        Object resourceName = metadata == null ? null : metadata.get(RESOURCE_NAME_METADATA_KEY);
        return resourceName == null ? document.getName() : resourceName.toString();
    }

    private RowSource select(String contentType) {
        for (RowSource reader : readers) {
            if (reader.supports(contentType)) {
                return reader;
            }
        }
        throw new IllegalArgumentException(
                "no reader supports " + contentType + ", supported content types: " + SUPPORTED_CONTENT_TYPES);
    }

    /**
     * The mapping's override wins; otherwise a generic stored type is refined by extension, because
     * Tika has no mimetype for jsonl or ndjson and types a .csv without the extension as text/plain.
     */
    static String effectiveContentType(String override, String storedType, String filename) {
        if (override != null) {
            return override;
        }
        if (!GENERIC_TYPES.contains(storedType)) {
            return storedType;
        }
        return Optional.ofNullable(TYPE_BY_EXTENSION.get(extension(filename))).orElse(storedType);
    }

    static Character delimiterByExtension(String filename) {
        return DELIMITER_BY_EXTENSION.get(extension(filename));
    }

    private static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Tika's TextAndCSVParser sniffs the delimiter at index time and records its name under
     * csv:delimiter, which reaches the metadata map as tika_metadata_csv_delimiter. Reading it beats
     * sniffing again here: it costs nothing and it cannot disagree with the delimiter Tika used to
     * produce the document's indexed text.
     */
    static Character delimiterFrom(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object name = metadata.get(DELIMITER_METADATA_KEY);
        return name == null ? null : DELIMITER_BY_TIKA_NAME.get(name.toString().toLowerCase(Locale.ROOT));
    }
}
