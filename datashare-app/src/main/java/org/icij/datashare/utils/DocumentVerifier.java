package org.icij.datashare.utils;

import org.icij.datashare.Entity;
import org.icij.datashare.HumanReadableSize;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.Duplicate;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;

import static org.icij.datashare.cli.DatashareCliOptions.EMBEDDED_DOCUMENT_DOWNLOAD_MAX_SIZE_OPT;

/**
 * This class is responsible for verifying properties and conditions of documents.
 */
public class DocumentVerifier {
    private static final Logger logger = LoggerFactory.getLogger(DocumentVerifier.class);

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
        return isRootDocumentSizeAllowed(document, document.getProject());
    }

    // The project the caller will actually serve from: the artifact cache must be probed under the
    // same project the source will be read under, or the gate can approve what the read cannot find.
    public boolean isRootDocumentSizeAllowed(Document document, Project servingProject) {
        if (document.isRootDocument()) {
            return true;
        }
        // A cached raw artifact is read straight off disk: the root is never opened, so its size is
        // irrelevant. Checked before the root lookup, so a cache hit costs one stat and no ES call.
        if (sources.hasCachedEmbeddedSource(servingProject, document)) {
            return true;
        }
        long maxSizeBytes = getEmbeddedDocumentDownloadMaxSizeBytes();
        Document rootDocument = getRootDocument(document);
        // An orphaned embed (root missing from the index, e.g. a mid-parse OOM that never wrote
        // the root) must not NPE into a 500: refuse the download, it's safe and diagnosable.
        if (rootDocument == null) {
            logger.warn("cannot verify root document size for document {}: root document {} not found",
                    document.getId(), document.getRootDocument());
            return false;
        }
        return rootDocument.getContentLength() < maxSizeBytes;
    }

    // Cheap "could this be served at all" probe for HEAD, which must not extract: either the
    // embedded bytes are already cached under servingProject (the project the caller will actually
    // read under, same reasoning as isRootDocumentSizeAllowed's overload), or the file we would
    // read (the document's own path for a root, the root's path for an embed) has to exist. An
    // embed whose root file is present can still fail to extract, which only the real read can
    // discover, so HEAD stays optimistic there.
    public boolean isSourceAvailable(Document document, Project servingProject) {
        return sources.hasCachedEmbeddedSource(servingProject, document) || Files.exists(document.getPath());
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
