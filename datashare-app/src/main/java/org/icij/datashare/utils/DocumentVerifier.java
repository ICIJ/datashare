package org.icij.datashare.utils;

import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Duplicate;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;

import static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT;

/**
 * This class is responsible for verifying properties and conditions of documents.
 */
public class DocumentVerifier {

    private static final String DEFAULT_MAX_SIZE = "1G";

    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final SourceExtractor sources;

    /**
     * Constructs a new DocumentVerifier with the provided indexer and propertiesProvider.
     *
     * @param indexer The indexer used to fetch document details.
     * @param propertiesProvider The provider used to fetch system properties.
     */
    public DocumentVerifier(Indexer indexer, PropertiesProvider propertiesProvider) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.sources = new SourceExtractor(propertiesProvider);
    }

    /**
     * Checks if the root document size is allowed based on the provided document's properties.
     * Also returns true when the embedded document's raw artifact is already cached, since serving
     * it from the cache never opens the root document.
     *
     * @param document The document to verify.
     * @return true if the root document size is allowed, false otherwise.
     */
    public boolean isRootDocumentSizeAllowed(Document document) {
        if (document.isRootDocument()) {
            return true;
        }
        // A cached raw artifact is read straight off disk: the root is never opened, so its size is
        // irrelevant. Checked before the root lookup, so a cache hit costs one stat and no ES call.
        if (sources.hasCachedEmbeddedSource(document.getProject(), document)) {
            return true;
        }
        long maxSizeBytes = getEmbeddedDocumentDownloadMaxSizeBytes();
        Document rootDocument = getRootDocument(document);
        return rootDocument.getContentLength() < maxSizeBytes;
    }

    /**
     * Retrieves the max size in bytes based on app properties.
     *
     * @return The max size in bytes.
     */
    private long getEmbeddedDocumentDownloadMaxSizeBytes() {
        String maxSize = propertiesProvider.get(EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT).orElse(DEFAULT_MAX_SIZE);
        return HumanReadableSize.parse(maxSize);
    }

    /**
     * Retrieves the root document for an embedded document even if the root is a duplicate.
     *
     * @return The max size in bytes.
     */
    private Document getRootDocument(Document document) {
        Entity entity = indexer.get(document.getProjectId(), document.getRootDocument());
        if (entity instanceof Duplicate) {
            return indexer.get(document.getProjectId(), ((Duplicate) entity).documentId);
        }
        return (Document) entity;
    }
}
