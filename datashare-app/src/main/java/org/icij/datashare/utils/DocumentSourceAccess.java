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
import java.io.InputStream;
import java.util.List;
import java.util.function.Function;

import static java.util.Optional.ofNullable;
import static net.codestory.http.constants.Headers.CONTENT_LENGTH;
import static net.codestory.http.errors.NotFoundException.notFoundIfNull;
import static org.icij.datashare.text.Project.isAllowed;
import static org.icij.datashare.text.Project.project;

/** The single decision for serving a document's source bytes: who may download, whether the
 *  document exists, whether its root is within the size limit, and how the payload is built.
 *  Shared by DocumentResource (/documents/src) and ArtifactResource (/artifacts/raw) so the
 *  download restriction cannot drift into a bypass on one of the two routes. */
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

    public Payload gated(final String project, final String id, final String routing,
                         final Context context, final Function<Document, Payload> whenAllowed) {
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
            InputStream from = new SourceExtractor(propertiesProvider, filterMetadata).getSource(project(index), doc);
            String contentType = ofNullable(doc.getContentType()).orElse(ContentTypes.get(doc.getPath().toFile().getName()));
            // OCR-routed embedded images are stored with a synthetic "image/ocr-<fmt>" content type
            // (the "ocr-" prefix routes them through the OCR parser). Serve the real media type so the
            // Content-Type header and the download filename extension are correct (otherwise ".bin").
            if (contentType != null && contentType.startsWith("image/ocr-")) {
                contentType = "image/" + contentType.substring("image/ocr-".length());
            }
            Payload payload = new Payload(contentType, from);
            if (!filterMetadata && doc.getContentLength() > 0) {
                payload.withHeader(CONTENT_LENGTH, String.valueOf(doc.getContentLength()));
            }
            String fileName = doc.isRootDocument() ? doc.getName() : doc.getId().substring(0, 10) + "." + FileExtension.get(contentType);
            return inline ? payload : payload.withHeader("Content-Disposition", "attachment;filename=\"" + fileName + "\"");
        } catch (FileNotFoundException | EmbeddedDocumentExtractor.ContentNotFoundException fnf) {
            logger.error("unable to read document source file", fnf);
            return Payload.notFound();
        }
    }
}
