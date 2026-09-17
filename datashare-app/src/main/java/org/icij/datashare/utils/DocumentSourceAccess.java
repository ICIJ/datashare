package org.icij.datashare.utils;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import net.codestory.http.Context;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import net.codestory.http.types.ContentTypes;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.Repository;
import org.icij.datashare.session.DatashareUser;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.FileExtension;
import org.icij.datashare.text.Project;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.SourceExtractor;
import org.icij.extract.extractor.EmbeddedDocumentExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Function;
import static java.util.Optional.ofNullable;
import static net.codestory.http.constants.Headers.CONTENT_LENGTH;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

/** The single decision for serving a document's source bytes: who may download, whether the
 *  document exists, whether its root is within the size limit, and how the payload is built.
 *  Shared by /documents/src (both verbs) and /artifacts/raw, so the rule cannot drift into a
 *  bypass on one of them. */
@Singleton
public class DocumentSourceAccess {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final Repository repository;
    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final DocumentVerifier documentVerifier;

    @Inject
    public DocumentSourceAccess(Repository repository, Indexer indexer, PropertiesProvider propertiesProvider) {
        this.repository = repository;
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.documentVerifier = new DocumentVerifier(indexer, propertiesProvider);
    }

    public Payload gated(final String project, final String id, final String routing, final Context context,
                         final Function<Document, Payload> whenAllowed) {
        boolean isProjectGranted = ((DatashareUser) context.currentUser()).isGranted(project);
        boolean isDownloadAllowed = isAllowed(repository.getProject(project), context.request().clientAddress());
        if (!isProjectGranted || !isDownloadAllowed) {
            return PayloadFormatter.error("You are not allowed to download this document", HttpStatus.FORBIDDEN);
        }
        List<String> sourceExcludes = List.of("content", "content_translated");
        Document document = notFoundIfNull(indexer.get(project, id, routing == null ? id : routing, sourceExcludes));
        if (!documentVerifier.isRootDocumentSizeAllowed(document, project(project))) {
            return PayloadFormatter.error("The file or its parent is too large", HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        }
        return whenAllowed.apply(document);
    }

    public boolean isSourceAvailable(Document document, Project servingProject) {
        return documentVerifier.isSourceAvailable(document, servingProject);
    }

    public Payload source(Document doc, String index, boolean inline, boolean filterMetadata) {
        try {
            SourceExtractor sources = new SourceExtractor(propertiesProvider, filterMetadata);
            InputStream from = sources.getSource(project(index), doc);
            String contentType =
                    ofNullable(doc.getContentType()).orElse(ContentTypes.get(doc.getPath().toFile().getName()));
            // OCR-routed embedded images are stored with a synthetic "image/ocr-<fmt>" content type
            // (the "ocr-" prefix routes them through the OCR parser). Serve the real media type so the
            // Content-Type header and the download filename extension are correct (otherwise ".bin").
            if (contentType != null && contentType.startsWith("image/ocr-")) {
                contentType = "image/" + contentType.substring("image/ocr-".length());
            }
            Payload payload = new Payload(contentType, from);
            // filter_metadata rewrites the payload, so the on-disk byte count is wrong for it:
            // send no header at all rather than a length the response will not honor.
            if (!filterMetadata) {
                long contentLength = servedContentLength(sources, doc, project(index));
                if (contentLength > 0) {
                    payload.withHeader(CONTENT_LENGTH, String.valueOf(contentLength));
                }
            }
            String fileName = doc.isRootDocument() ? doc.getName() :
                              doc.getId().substring(0, 10) + "." + FileExtension.get(contentType);
            return inline ? payload :
                   payload.withHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");
        } catch (FileNotFoundException | EmbeddedDocumentExtractor.ContentNotFoundException fnf) {
            logger.error("unable to read document source file", fnf);
            return Payload.notFound();
        }
    }

    // The length of the bytes about to be served, not the indexed contentLength: the indexed
    // value goes stale when a root file changes on disk and is usually absent for embedded
    // documents. Same fork as the download gate (root / cached artifact / live parse), one
    // stat on a path the request just opened.
    private long servedContentLength(SourceExtractor sources, Document doc, Project servingProject) {
        if (!doc.isRootDocument()) {
            long cachedLength = sources.cachedEmbeddedSourceLength(servingProject, doc);
            return cachedLength > 0 ? cachedLength : doc.getContentLength();
        }
        try {
            long diskLength = Files.size(doc.getPath());
            // Tika indexed Files.size at extraction time, so a mismatch means the file changed
            // under us and is worth a manual look. Roots only: for embeds, a Tika-declared size
            // can legitimately differ from the bytes we cached, comparing would just be noise.
            if (doc.getContentLength() > 0 && diskLength != doc.getContentLength()) {
                logger.warn("document {} changed on disk: indexed contentLength is {} but serving {} bytes",
                            doc.getId(), doc.getContentLength(), diskLength);
            }
            return diskLength;
        } catch (IOException e) {
            return doc.getContentLength();
        }
    }
}
