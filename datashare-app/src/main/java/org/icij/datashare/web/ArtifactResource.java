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

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Optional.ofNullable;
import static org.icij.datashare.web.errors.ForbiddenException.requireGranted;

/** Serves per-document artifacts from artifactDir, driven by each document's manifest.json.
 *  Derived types (page, structure) are strict-store: only a COMPLETE entry is served, anything
 *  else is a 404. `raw` goes through the same gate as /documents/src, because a root document's
 *  raw entry is EMPTY by design and strict gating would 404 every root document. */
@Singleton
@Prefix("/api")
public class ArtifactResource {
    // Extensions are fixed by type (convention §71). The map is the ?format= whitelist and content-type
    // lookup; the list is the order of record for disk probing and for the error message, because
    // Map.of has no defined iteration order.
    private static final Map<String, String> STRUCTURE_CONTENT_TYPES = Map.of(
            "md", "text/markdown;charset=UTF-8",
            "xhtml", "application/xhtml+xml;charset=UTF-8");
    private static final List<String> STRUCTURE_FORMATS = List.of("md", "xhtml");

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
        return manifest(project, id, routing, context, ArtifactType.PAGE, List.of());
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
        return payload(project, id, page, routing, context, ArtifactType.PAGE, "txt", "text/plain;charset=UTF-8");
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
        return manifest(project, id, routing, context, ArtifactType.STRUCTURE, STRUCTURE_FORMATS);
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
        String extension = ofNullable(format).filter(value -> !value.isBlank()).orElse("md");
        String contentType = STRUCTURE_CONTENT_TYPES.get(extension);
        if (contentType == null) {
            // A bad format is a request error: 404 on this route means "no such page".
            return PayloadFormatter.error("unsupported format '" + extension + "'; supported formats: "
                    + String.join(", ", STRUCTURE_FORMATS), HttpStatus.BAD_REQUEST);
        }
        Payload payload = payload(project, id, page, routing, context, ArtifactType.STRUCTURE, extension, contentType);
        // The on-disk XHTML is written pre-sanitized by the structure producer; these headers are
        // the serving side's defense in depth for a payload written by another producer.
        return "xhtml".equals(extension)
                ? payload.withHeader("Content-Security-Policy", "default-src 'none'; sandbox")
                        .withHeader("X-Content-Type-Options", "nosniff")
                : payload;
    }

    // Resolves the content-addressed dir of a granted, existing document. Returns null when the
    // document is unknown or artifactDir is unset (both 404); throws ForbiddenException (403) when
    // the project is not granted. The document is always resolved through the indexer first, so a
    // URL cannot probe arbitrary digests or another project's data.
    private Path docArtifactDir(final String project, final String id, final String routing, final Context context) {
        requireGranted(context, project);
        Document document = indexer.get(project, id, ofNullable(routing).orElse(id), List.of("content", "content_translated"));
        if (document == null) {
            return null;
        }
        return propertiesProvider.get(DatashareCliOptions.ARTIFACT_DIR_OPT)
                .map(dir -> ArtifactPath.dir(Path.of(dir).resolve(project), document.getId()))
                .orElse(null);
    }

    private Payload manifest(final String project, final String id, final String routing, final Context context,
                             final ArtifactType type, final List<String> formats) throws IOException {
        Path docArtifactDir = docArtifactDir(project, id, routing, context);
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        ManifestEntry entry = reader.servableEntry(docArtifactDir, type);
        if (entry == null || entry.total() == null) {
            return Payload.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pages", entry.total());
        if (!formats.isEmpty()) {
            body.put("formats", reader.formats(docArtifactDir, type, entry, formats));
        }
        return PayloadFormatter.json(body).withCode(200);
    }

    private Payload payload(final String project, final String id, final String page, final String routing,
                            final Context context, final ArtifactType type, final String extension,
                            final String contentType) throws IOException {
        Path docArtifactDir = docArtifactDir(project, id, routing, context);
        if (docArtifactDir == null) {
            return Payload.notFound();
        }
        ManifestEntry entry = reader.servableEntry(docArtifactDir, type);
        if (entry == null) {
            return Payload.notFound();
        }
        int pageNumber = parsePageNumber(page);
        byte[] bytes = pageNumber < 1 ? null : reader.page(docArtifactDir, type, entry, pageNumber, extension);
        return bytes == null ? Payload.notFound() : new Payload(contentType, bytes).withCode(200);
    }

    // -1 for anything that is not a positive integer, so non-numeric and out-of-range share the
    // same 404 answer.
    private static int parsePageNumber(String page) {
        try {
            return Integer.parseInt(page);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
