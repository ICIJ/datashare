package org.icij.datashare.web;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import net.codestory.http.Context;
import net.codestory.http.annotations.Get;
import net.codestory.http.annotations.Prefix;
import net.codestory.http.constants.HttpStatus;
import net.codestory.http.payload.Payload;
import org.icij.datashare.PropertiesProvider;
import org.icij.datashare.cli.DatashareCliOptions;
import org.icij.datashare.text.Document;
import org.icij.datashare.text.artifact.ArtifactReader;
import org.icij.datashare.text.artifact.ArtifactType;
import org.icij.datashare.text.artifact.FilesystemManifestRepository;
import org.icij.datashare.text.artifact.ManifestEntry;
import org.icij.datashare.text.indexing.Indexer;
import org.icij.datashare.text.indexing.elasticsearch.ArtifactPath;
import org.icij.datashare.utils.DocumentSourceAccess;
import org.icij.datashare.utils.PayloadFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.lang.Boolean.parseBoolean;
import static java.util.Collections.unmodifiableMap;
import static java.util.Optional.ofNullable;
import static org.icij.datashare.web.errors.ForbiddenException.requireGranted;

/** Serves per-document artifacts from artifactDir, driven by each document's manifest.json.
 *  Derived types (page, structure) are strict-store: only a COMPLETE entry is served, anything
 *  else is a 404. `raw` goes through the same gate as /documents/src, because a root document's
 *  raw entry is EMPTY by design and strict gating would 404 every root document. */
@Singleton
@Prefix("/api")
public class ArtifactResource {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArtifactResource.class);
    // Extensions are fixed by type (convention §71). One ordered map, so adding a format is one
    // edit: its key set is the ?format= whitelist, the disk-probe order and the error-message order.
    private static final String MARKDOWN = "md";
    private static final String XHTML = "xhtml";
    private static final Map<String, String> STRUCTURE_CONTENT_TYPES = structureContentTypes();

    private final Indexer indexer;
    private final PropertiesProvider propertiesProvider;
    private final DocumentSourceAccess sources;
    private final ArtifactReader reader = new ArtifactReader(new FilesystemManifestRepository());

    @Inject
    public ArtifactResource(Indexer indexer, PropertiesProvider propertiesProvider, DocumentSourceAccess sources) {
        this.indexer = indexer;
        this.propertiesProvider = propertiesProvider;
        this.sources = sources;
    }

    @Operation(description = "Fetches the number of persisted plain-text pages for a document.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "JSON {\"pages\": N}")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, artifactDir, or a complete page artifact is not found")
    @Get("/:project/artifacts/page/:id?routing=:routing")
    public Payload pageManifest(final String project, final String id, final String routing, final Context context) throws IOException {
        requireGranted(context, project);
        return manifest(project, id, routing, ArtifactType.PAGE, List.of());
    }

    @Operation(description = "Fetches one persisted plain-text page of a document.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "page", description = "1-based page number", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "the page as text/plain")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, the artifact, or the page is not found")
    @Get("/:project/artifacts/page/:id/:page?routing=:routing")
    public Payload pageContent(final String project, final String id, final String page,
                               final String routing, final Context context) throws IOException {
        requireGranted(context, project);
        return payload(project, id, page, routing, ArtifactType.PAGE, "txt", "text/plain;charset=UTF-8");
    }

    @Operation(description = "Fetches the number of structure pages for a document and the formats available on disk.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "JSON {\"pages\": N, \"formats\": [\"md\", \"xhtml\"]}")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, artifactDir, or a complete structure artifact is not found")
    @Get("/:project/artifacts/structure/:id?routing=:routing")
    public Payload structureManifest(final String project, final String id, final String routing, final Context context) throws IOException {
        requireGranted(context, project);
        return manifest(project, id, routing, ArtifactType.STRUCTURE, STRUCTURE_CONTENT_TYPES.keySet());
    }

    @Operation(description = "Fetches one structure page of a document, as Markdown (default) or XHTML.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "page", description = "1-based page number", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY),
                    @Parameter(name = "format", description = "md (default) or xhtml", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "the page as text/markdown or application/xhtml+xml")
    @ApiResponse(responseCode = "400", description = "if format is not one of md, xhtml")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project")
    @ApiResponse(responseCode = "404", description = "if the document, the artifact, the page, or that format is not found")
    @Get("/:project/artifacts/structure/:id/:page?routing=:routing&format=:format")
    public Payload structurePage(final String project, final String id, final String page, final String routing,
                                 final String format, final Context context) throws IOException {
        // Membership gates before format validation, same as every other route here: a non-member
        // must see 403 regardless of what else is wrong with the request.
        requireGranted(context, project);
        String extension = ofNullable(format).filter(value -> !value.isBlank()).orElse(MARKDOWN);
        String contentType = STRUCTURE_CONTENT_TYPES.get(extension);
        if (contentType == null) {
            // A bad format is a request error: 404 on this route means "no such page".
            return PayloadFormatter.error("unsupported format '" + extension + "'; supported formats: "
                    + String.join(", ", STRUCTURE_CONTENT_TYPES.keySet()), HttpStatus.BAD_REQUEST);
        }
        Payload payload = payload(project, id, page, routing, ArtifactType.STRUCTURE, extension, contentType);
        // The on-disk XHTML is written pre-sanitized by the structure producer; this is the serving
        // side's defense in depth for a payload written by another producer.
        return XHTML.equals(extension)
                ? payload.withHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                : payload;
    }

    @Operation(description = "Fetches a document's raw (embedded or source) bytes. Same access rules as /documents/src: project membership, the project's download restriction, and the root-document size limit.",
            parameters = {
                    @Parameter(name = "project", description = "the project id", in = ParameterIn.PATH),
                    @Parameter(name = "id", description = "the document id", in = ParameterIn.PATH),
                    @Parameter(name = "routing", description = "routing key if not a root document", in = ParameterIn.QUERY),
                    @Parameter(name = "inline", description = "if true, serve without the attachment disposition", in = ParameterIn.QUERY)
            }
    )
    @ApiResponse(responseCode = "200", description = "the raw bytes, with the document's content type")
    @ApiResponse(responseCode = "403", description = "forbidden if the user doesn't have access to the project or downloads are restricted")
    @ApiResponse(responseCode = "404", description = "if no document is found or its bytes cannot be read")
    @ApiResponse(responseCode = "413", description = "if the root document is too large and no raw artifact is cached for this embedded document")
    @Get("/:project/artifacts/raw/:id?routing=:routing&inline=:inline")
    public Payload raw(final String project, final String id, final String routing,
                       final String inline, final Context context) {
        boolean serveInline = parseBoolean(inline);
        return sources.gated(project, id, routing, context,
                document -> sources.source(document, project, serveInline, false));
    }

    // Resolves the content-addressed dir of an existing document, for a route that has already
    // checked project membership. Returns null when the document is unknown or artifactDir is unset
    // (both 404). The document is always resolved through the indexer first, so a URL cannot probe
    // arbitrary digests or another project's data.
    private Path docArtifactDir(final String project, final String id, final String routing) {
        Document document = indexer.get(project, id, ofNullable(routing).orElse(id), List.of("content", "content_translated"));
        if (document == null) {
            return null;
        }
        Optional<String> artifactDir = propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT);
        if (artifactDir.isEmpty()) {
            // Without artifactDir every artifact route answers 404 for every document, which reads
            // exactly like "this document has no artifacts": say so once per request instead.
            LOGGER.warn("{} is unset, so no artifact can be served for document {} of project {}",
                    DatashareCliOptions.ARTIFACT_DIR_OPT, id, project);
            return null;
        }
        return ArtifactPath.dir(ArtifactPath.projectRoot(Path.of(artifactDir.get()), project), document.getId());
    }

    private static Map<String, String> structureContentTypes() {
        Map<String, String> contentTypes = new LinkedHashMap<>();
        contentTypes.put(MARKDOWN, "text/markdown;charset=UTF-8");
        contentTypes.put(XHTML, "application/xhtml+xml;charset=UTF-8");
        return unmodifiableMap(contentTypes);
    }

    private Payload manifest(final String project, final String id, final String routing,
                             final ArtifactType type, final Collection<String> formats) throws IOException {
        Path docArtifactDir = docArtifactDir(project, id, routing);
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        ManifestEntry entry = reader.servableEntry(docArtifactDir, type);
        Integer pages = entry == null ? null : reader.servableTotal(docArtifactDir, type, entry);
        if (pages == null) {
            return Payload.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pages", pages);
        if (!formats.isEmpty()) {
            body.put("formats", reader.formats(docArtifactDir, type, entry, formats));
        }
        return PayloadFormatter.json(body);
    }

    private Payload payload(final String project, final String id, final String page, final String routing,
                            final ArtifactType type, final String extension,
                            final String contentType) throws IOException {
        Path docArtifactDir = docArtifactDir(project, id, routing);
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        ManifestEntry entry = reader.servableEntry(docArtifactDir, type);
        if (entry == null) {
            return Payload.notFound();
        }
        byte[] bytes = reader.page(docArtifactDir, type, entry, parsePageNumber(page), extension);
        if (bytes == null) {
            return Payload.notFound();
        }
        // Every artifact payload is text derived from an ingested document, served from this origin:
        // a browser that sniffs its way to another type could execute a malicious document.
        return new Payload(contentType, bytes).withHeader("X-Content-Type-Options", "nosniff");
    }

    private static int parsePageNumber(String page) {
        try {
            return Integer.parseInt(page);
        } catch (NumberFormatException notANumber) {
            // 0 is out of range for the reader, so a non-numeric page shares the out-of-range 404.
            return 0;
        }
    }
}
